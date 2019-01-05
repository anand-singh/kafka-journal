package akka.persistence.kafka.journal

import java.time.Instant

import cats.implicits._
import akka.actor.ActorSystem
import akka.persistence.journal.Tagged
import akka.persistence.kafka.journal.KafkaJournal.Metrics
import akka.persistence.{AtomicWrite, PersistentRepr}
import cats.effect.{Concurrent, ContextShift, IO, Resource}
import com.evolutiongaming.concurrent.FutureHelper._
import com.evolutiongaming.concurrent.async.Async
import com.evolutiongaming.kafka.journal.FoldWhile._
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.AsyncHelper._
import com.evolutiongaming.kafka.journal.eventual.EventualJournal
import com.evolutiongaming.kafka.journal.eventual.cassandra.EventualCassandra
import com.evolutiongaming.kafka.journal.util.{FromFuture, ToFuture}
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.safeakka.actor.ActorLog
import com.evolutiongaming.scassandra.CreateCluster
import com.evolutiongaming.skafka.ClientId
import com.evolutiongaming.skafka.consumer.Consumer
import com.evolutiongaming.skafka.producer.Producer

import scala.collection.immutable.Seq
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.Try
import scala.util.control.NonFatal

trait JournalAdapter {

  def write(messages: Seq[AtomicWrite]): Future[List[Try[Unit]]]

  def delete(persistenceId: String, to: SeqNr): Future[Unit]

  def lastSeqNr(persistenceId: String, from: SeqNr): Future[Option[SeqNr]]

  def replay(persistenceId: String, range: SeqRange, max: Long)(f: PersistentRepr => Unit): Future[Unit]
}

object JournalAdapter {

  def adapterOf[F[_] : Concurrent : ContextShift : FromFuture : ToFuture](
    toKey: ToKey,
    origin: Option[Origin],
    serializer: EventSerializer,
    config: KafkaJournalConfig,
    metrics: Metrics,
    log: ActorLog)(implicit system: ActorSystem, ec: ExecutionContextExecutor): Resource[F, JournalAdapter] = {

    val blocking = system.dispatchers.lookup(config.blockingDispatcher)

    val producer = {
      val producerConfig = config.journal.producer
      val producerMetrics = for {
        metrics <- metrics.producer
      } yield {
        val clientId = producerConfig.common.clientId getOrElse "journal"
        metrics(clientId)
      }
      KafkaProducer.of[F](producerConfig, blocking, producerMetrics)
    }

    for {
      producer <- producer
    } yield {
      val topicConsumer = {
        val consumerConfig = config.journal.consumer
        val consumerMetrics = for {
          metrics <- metrics.consumer
        } yield {
          val clientId = consumerConfig.common.clientId getOrElse "journal"
          metrics(clientId)
        }
        TopicConsumer[F](consumerConfig, blocking, metrics = consumerMetrics)
      }

      val eventualJournal: EventualJournal[Async] = {
        val cassandraConfig = config.cassandra
        val cluster = CreateCluster(cassandraConfig.client)
        implicit val session = Await.result(cluster.connect(), config.connectTimeout)
        system.registerOnTermination {
          val result = for {
            _ <- session.close()
            _ <- cluster.close()
          } yield {}
          try {
            Await.result(result, config.stopTimeout)
          } catch {
            case NonFatal(failure) => log.error(s"failed to shutdown cassandra $failure", failure)
          }
        }

        {
          val actorLog = ActorLog(system, EventualJournal.getClass)
          implicit val log = Log.async(actorLog)
          val journal = {
            val journal = EventualCassandra(cassandraConfig, actorLog, origin)
            EventualJournal(journal)
          }
          metrics.eventual.fold(journal) { EventualJournal(journal, _) }
        }
      }

      // TODO resource
      val headCache = {
        if (config.headCache) {
          HeadCacheAsync(config.journal.consumer, eventualJournal, blocking)
        } else {
          HeadCache.empty[Async]
        }
      }

      system.registerOnTermination {
        try {
          Await.result(headCache.close.future, config.stopTimeout)
        } catch {
          case NonFatal(failure) => log.error(s"failed to shutdown headCache $failure", failure)
        }
      }

      val journal = {
        val journal = Journal(
          producer = producer,
          origin = origin,
          topicConsumer = topicConsumer,
          eventual = eventualJournal,
          pollTimeout = config.journal.pollTimeout,
          headCache = headCache)

        metrics.journal.fold(journal) { Journal(journal, _) }
      }
      JournalAdapter(log, toKey, journal, serializer)
    }
  }

  def apply(
    log: ActorLog,
    toKey: ToKey,
    journal: Journal[Async],
    serializer: EventSerializer)(implicit ec: ExecutionContext): JournalAdapter = {
    
    new JournalAdapter {

      def write(atomicWrites: Seq[AtomicWrite]) = {
        val timestamp = Instant.now()
        Future {
          val persistentReprs = for {
            atomicWrite <- atomicWrites
            persistentRepr <- atomicWrite.payload
          } yield {
            persistentRepr
          }
          if (persistentReprs.isEmpty) Future.nil
          else {
            val persistenceId = persistentReprs.head.persistenceId
            val key = toKey(persistenceId)

            log.debug {
              val first = persistentReprs.head.sequenceNr
              val last = persistentReprs.last.sequenceNr
              val seqNr = if (first == last) s"seqNr: $first" else s"seqNrs: $first..$last"
              s"$persistenceId write, $seqNr"
            }

            val events = for {
              persistentRepr <- persistentReprs
            } yield {
              serializer.toEvent(persistentRepr)
            }
            val nel = Nel(events.head, events.tail.toList)
            val result = journal.append(key, nel, timestamp)
            result.map(_ => Nil).future
          }
        }.flatten
      }

      def delete(persistenceId: PersistenceId, to: SeqNr) = {
        log.debug(s"$persistenceId delete, to: $to")

        val timestamp = Instant.now()
        val key = toKey(persistenceId)
        journal.delete(key, to, timestamp).unit.future
      }

      def replay(persistenceId: PersistenceId, range: SeqRange, max: Long)
        (callback: PersistentRepr => Unit): Future[Unit] = {

        log.debug(s"$persistenceId replay, range: $range")

        val key = toKey(persistenceId)
        val fold: Fold[Long, Event] = (count, event) => {
          val seqNr = event.seqNr
          if (seqNr <= range.to && count < max) {
            val persistentRepr = serializer.toPersistentRepr(persistenceId, event)
            callback(persistentRepr)
            val countNew = count + 1
            countNew switch countNew != max
          } else {
            count.stop
          }
        }
        val async = journal.read(key, range.from, 0l)(fold)
        async.unit.future
      }

      def lastSeqNr(persistenceId: PersistenceId, from: SeqNr) = {
        log.debug(s"$persistenceId lastSeqNr, from: $from")

        val key = toKey(persistenceId)
        val pointer = for {
          pointer <- journal.pointer(key)
        } yield for {
          pointer <- pointer
          if pointer >= from
        } yield {
          pointer
        }

        pointer.future
      }
    }
  }
}

object PayloadAndTags {

  def apply(payload: Any): (Any, Tags) = payload match {
    case Tagged(payload, tags) => (payload, tags)
    case _                     => (payload, Set.empty)
  }


  final case class Metrics(
    journal: Option[Journal.Metrics[Async]] = None,
    eventual: Option[EventualJournal.Metrics[Async]] = None,
    producer: Option[ClientId => Producer.Metrics] = None,
    consumer: Option[ClientId => Consumer.Metrics] = None)

  object Metrics {
    val Empty: Metrics = Metrics()
  }
}

