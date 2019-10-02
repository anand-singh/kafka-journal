package com.evolutiongaming.kafka.journal.eventual.cassandra

import java.time.Instant

import cats.data.{NonEmptyList => Nel}
import cats.implicits._
import cats.{Id, Parallel}
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.eventual.EventualJournalSpec._
import com.evolutiongaming.kafka.journal.eventual.{EventualJournal, EventualJournalSpec, TopicPointers}
import com.evolutiongaming.kafka.journal.util.ConcurrentOf
import com.evolutiongaming.skafka.{Offset, Partition, Topic}
import com.evolutiongaming.sstream.FoldWhile._
import com.evolutiongaming.sstream.Stream

class EventualCassandraSpec extends EventualJournalSpec {
  import EventualCassandraSpec._

  "EventualCassandra" when {
    for {
      segmentSize <- List(SegmentSize.min, SegmentSize.default, SegmentSize.max)
      delete      <- List(true, false)
    } {
      s"segmentSize: $segmentSize, delete: $delete" should {
        test[StateT] { test =>
          val (_, result) = test(journals(segmentSize, delete)).run(State.empty)
          result.pure[StateT]
        }
      }
    }
  }
}

object EventualCassandraSpec {

  val selectHead: HeadStatement.Select[StateT] = {
    key: Key => {
      StateT { state =>
        val head = state.heads.get(key)
        (state, head)
      }
    }
  }


  val selectPointers: PointerStatement.SelectAll[StateT] = {
    topic: Topic => {
      StateT { state =>
        val pointer = state.pointers.getOrElse(topic, TopicPointers.empty)
        (state, pointer.values)
      }
    }
  }


  implicit val parallel: Parallel[StateT] = Parallel.identity[StateT]


  implicit val eventualJournal: EventualJournal[StateT] = {

    val selectRecords = new JournalStatement.SelectRecords[StateT] {

      def apply(key: Key, segment: SegmentNr, range: SeqRange) = new Stream[StateT, EventRecord] {

        def foldWhileM[L, R](l: L)(f: (L, EventRecord) => StateT[Either[L, R]]) = {
          StateT { state =>
            val events = state.journal.events(key, segment)
            val result = events.foldWhileM[StateT, L, R](l) { (l, event) =>
              val seqNr = event.event.seqNr
              if (range contains seqNr) f(l, event)
              else l.asLeft[R].pure[StateT]
            }
            (state, result)
          }.flatten
        }
      }
    }

    val statements = EventualCassandra.Statements(
      records = selectRecords,
      head = selectHead,
      pointers = selectPointers)

    EventualCassandra[StateT](statements)
  }


  def journals(segmentSize: SegmentSize, delete: Boolean): Journals[StateT] = {

    val replicatedJournal = {

      val insertRecords: JournalStatement.InsertRecords[StateT] = {
        (key: Key, segment: SegmentNr, records: Nel[EventRecord]) => {
          StateT { state =>
            val journal = state.journal
            val events = journal.events(key, segment)
            val updated = events ++ records.toList.sortBy(_.event.seqNr)
            val state1 = state.copy(journal = journal.updated((key, segment), updated))
            (state1, ())
          }
        }
      }


      val deleteRecords: JournalStatement.DeleteRecords[StateT] = {
        (key: Key, segment: SegmentNr, seqNr: SeqNr) => {
          StateT { state =>
            val state1 = {
              if (delete) {
                val journal = state.journal
                val events = journal.events(key, segment)
                val updated = events.dropWhile(_.event.seqNr <= seqNr)
                state.copy(journal = journal.updated((key, segment), updated))
              } else {
                state
              }
            }
            (state1, ())
          }
        }
      }


      val insertHead: HeadStatement.Insert[StateT] = {
        (key: Key, _: Instant, head: Head, _: Option[Origin]) => {
          StateT { state =>
            val state1 = state.copy(heads = state.heads.updated(key, head))
            (state1, ())
          }
        }
      }


      val updateHead: HeadStatement.Update[StateT] = {
        (key: Key, partitionOffset: PartitionOffset, _: Instant, seqNr: SeqNr, deleteTo: SeqNr) => {
          StateT { state =>
            val heads = state.heads
            val state1 = for {
              head <- heads.get(key)
            } yield {
              val head1 = head.copy(partitionOffset = partitionOffset, seqNr = seqNr, deleteTo = Some(deleteTo))
              state.copy(heads = heads.updated(key, head1))
            }

            (state1 getOrElse state, ())
          }
        }
      }


      val updateSeqNr: HeadStatement.UpdateSeqNr[StateT] = {
        (key: Key, partitionOffset: PartitionOffset, _: Instant, seqNr: SeqNr) => {
          StateT { state =>
            val heads = state.heads
            val state1 = for {
              head <- heads.get(key)
            } yield {
              val head1 = head.copy(partitionOffset = partitionOffset, seqNr = seqNr)
              state.copy(heads = heads.updated(key, head1))
            }
            (state1 getOrElse state, ())
          }
        }
      }


      val updateDeleteTo: HeadStatement.UpdateDeleteTo[StateT] = {
        (key: Key, partitionOffset: PartitionOffset, _: Instant, deleteTo: SeqNr) => {
          StateT { state =>
            val heads = state.heads
            val state1 = for {
              head <- heads.get(key)
            } yield {
              val head1 = head.copy(partitionOffset = partitionOffset, deleteTo = Some(deleteTo))
              state.copy(heads = heads.updated(key, head1))
            }

            (state1 getOrElse state, ())
          }
        }
      }


      val insertPointer: PointerStatement.Insert[StateT] = {
        (topic: Topic, partition: Partition, offset: Offset, _: Instant, _: Instant) => {
          StateT { state =>
            val pointers = state.pointers
            val topicPointers = pointers.getOrElse(topic, TopicPointers.empty)
            val updated = topicPointers.copy(values = topicPointers.values.updated(partition, offset))
            val pointers1 = pointers.updated(topic, updated)
            (state.copy(pointers = pointers1), ())
          }
        }
      }


      val updatePointer: PointerStatement.Update[StateT] = {
        (topic: Topic, partition: Partition, offset: Offset, _: Instant) => {
          StateT { state =>
            val pointers = state.pointers
            val topicPointers = pointers.getOrElse(topic, TopicPointers.empty)
            val updated = topicPointers.copy(values = topicPointers.values.updated(partition, offset))
            val pointers1 = pointers.updated(topic, updated)
            (state.copy(pointers = pointers1), ())
          }
        }
      }


      val selectPointer: PointerStatement.Select[StateT] = {
        (topic: Topic, partition: Partition) => {
          StateT { state =>
            val offset = state
              .pointers
              .getOrElse(topic, TopicPointers.empty)
              .values
              .get(partition)
            (state, offset)
          }
        }
      }


      val selectPointersIn: PointerStatement.SelectIn[StateT] = {
        (topic: Topic, partitions: Nel[Partition]) => {
          StateT { state =>
            val pointers = state
              .pointers
              .getOrElse(topic, TopicPointers.empty)
              .values
            val result = for {
              partition <- partitions.toList
              offset    <- pointers.get(partition)
            } yield {
              (partition, offset)
            }

            (state, result.toMap)
          }
        }
      }


      val selectTopics: PointerStatement.SelectTopics[StateT] = {
        () => {
          StateT { state =>
            (state, state.pointers.keys.toList)
          }
        }
      }

      val statements = ReplicatedCassandra.Statements(
        insertRecords = insertRecords,
        deleteRecords = deleteRecords,
        insertHead = insertHead,
        selectHead = selectHead,
        updateHead = updateHead,
        updateSeqNr = updateSeqNr,
        updateDeleteTo = updateDeleteTo,
        selectPointer = selectPointer,
        selectPointersIn = selectPointersIn,
        selectPointers = selectPointers,
        insertPointer = insertPointer,
        updatePointer = updatePointer,
        selectTopics = selectTopics)

      implicit val concurrentId = ConcurrentOf.fromMonad[StateT]
      ReplicatedCassandra(segmentSize, statements)
    }

    Journals(eventualJournal, replicatedJournal)
  }


  final case class State(
    journal: Map[(Key, SegmentNr), List[EventRecord]],
    heads: Map[Key, Head],
    pointers: Map[Topic, TopicPointers])

  object State {
    val empty: State = State(journal = Map.empty, heads = Map.empty, pointers = Map.empty)
  }


  type StateT[A] = cats.data.StateT[Id, State, A]

  object StateT {
    def apply[A](f: State => (State, A)): StateT[A] = cats.data.StateT[Id, State, A](f)
  }


  implicit class JournalOps(val self: Map[(Key, SegmentNr), List[EventRecord]]) extends AnyVal {

    def events(key: Key, segment: SegmentNr): List[EventRecord] = {
      val composite = (key, segment)
      self.getOrElse(composite, Nil)
    }
  }
}
