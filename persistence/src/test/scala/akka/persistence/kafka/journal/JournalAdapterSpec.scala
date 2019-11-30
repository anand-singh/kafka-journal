package akka.persistence.kafka.journal

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.persistence.{AtomicWrite, PersistentRepr}
import cats.Id
import cats.data.{NonEmptyList => Nel}
import cats.effect.Clock
import cats.implicits._
import com.evolutiongaming.catshelper.ClockHelper._
import com.evolutiongaming.catshelper.Log
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.sstream.Stream
import org.scalatest.{FunSuite, Matchers}
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.duration._

class JournalAdapterSpec extends FunSuite with Matchers {
  import JournalAdapterSpec.StateT._
  import JournalAdapterSpec._

  private val eventSerializer = EventSerializer.const[StateT](event, persistentRepr)

  private val aws = List(
    AtomicWrite(List(persistentRepr)),
    AtomicWrite(List(persistentRepr)))

  private val appendMetadataOf = {
    val metadata = AppendMetadata(expireAfter.some, recordMetadata.data, headers)
    AppendMetadataOf.const(metadata.pure[StateT])
  }

  private val journalAdapter = JournalAdapter[StateT](StateT.JournalStateF, toKey, eventSerializer, appendMetadataOf)

  private def appendOf(key: Key, events: Nel[Event]) = {
    Append(key, events, timestamp, expireAfter.some, recordMetadata, headers)
  }

  test("write") {
    val (data, result) = journalAdapter.write(aws).run(State.empty)
    result shouldEqual Nil
    data shouldEqual State(appends = List(appendOf(key1, Nel.of(event, event))))
  }

  test("delete") {
    val (data, _) = journalAdapter.delete(persistenceId, SeqNr.max).run(State.empty)
    data shouldEqual State(deletes = List(Delete(key1, SeqNr.max, timestamp)))
  }

  test("lastSeqNr") {
    val (data, result) = journalAdapter.lastSeqNr(persistenceId, SeqNr.max).run(State.empty)
    result shouldEqual None
    data shouldEqual State(pointers = List(Pointer(key1)))
  }

  test("replay") {
    val range = SeqRange(from = SeqNr.min, to = SeqNr.max)
    val initial = State(events = List(eventRecord))
    val f = (a: PersistentRepr) => StateT { s =>
      val s1 = s.copy(replayed = a :: s.replayed)
      (s1, ())
    }
    val (data, _) = journalAdapter.replay(persistenceId, range, Int.MaxValue)(f).run(initial)
    data shouldEqual State(reads = List(Read(key1, SeqNr.min)), replayed = List(persistentRepr))
  }

  test("withBatching") {
    val grouping = new Batching[StateT] {
      def apply(aws: List[AtomicWrite]) = aws.map(aw => List(aw)).pure[StateT]
    }
    val (data, result) = journalAdapter
      .withBatching(grouping)
      .write(aws).run(State.empty)
    result shouldEqual Nil
    data shouldEqual State(appends = List(
      appendOf(key1, Nel.of(event)),
      appendOf(key1, Nel.of(event))))
  }
}

object JournalAdapterSpec {

  private val timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS)
  private val expireAfter = 1.day
  private val toKey = ToKey.default[StateT]
  private val key1 = Key(id = "id", topic = "journal")
  private val event = Event(SeqNr.min)
  private val partitionOffset = PartitionOffset.empty
  private val persistenceId = "id"
  private val persistentRepr = PersistentRepr(None, persistenceId = persistenceId)
  private val recordMetadata = RecordMetadata(Json.obj(("key", "value")).some)
  private val headers = Headers(("key", "value"))
  private val origin = Origin("origin")
  private val eventRecord = EventRecord(event, timestamp, partitionOffset, origin.some, recordMetadata, headers)

  sealed abstract class Action

  object Action {
    final case class Purge(key: Key) extends Action
  }

  final case class Append(
    key: Key,
    events: Nel[Event],
    timestamp: Instant,
    expireAfter: Option[FiniteDuration],
    metadata: RecordMetadata,
    headers: Headers)

  final case class Read(key: Key, from: SeqNr)

  final case class Delete(key: Key, to: SeqNr, timestamp: Instant)

  final case class Pointer(key: Key)

  final case class State(
    events: List[EventRecord] = List.empty,
    appends: List[Append] = List.empty,
    pointers: List[Pointer] = List.empty,
    deletes: List[Delete] = List.empty,
    reads: List[Read] = List.empty,
    replayed: List[PersistentRepr] = List.empty,
    actions: List[Action] = List.empty)

  object State {

    val empty: State = State()

    implicit class StateOps(val self: State) extends AnyVal {

      def append(action: Action): State = self.copy(actions = action :: self.actions)
    }
  }


  type StateT[A] = cats.data.StateT[Id, State, A]

  object StateT {

    implicit val LogStateF: Log[StateT] = Log.empty[StateT]

    implicit val clockStateF: Clock[StateT] = Clock.const(nanos = 0, millis = timestamp.toEpochMilli) // TODO add Instant as argument

    implicit val JournalStateF: Journal[StateT] = new Journal[StateT] {

      def append(
        key: Key,
        events: Nel[Event],
        expireAfter: Option[FiniteDuration],
        metadata: Option[JsValue],
        headers: Headers
      ) = {
        StateT { state =>
          val append = Append(key, events, timestamp, expireAfter, RecordMetadata(metadata), headers)
          val s1 = state.copy(appends = append :: state.appends)
          (s1, partitionOffset)
        }
      }

      def read(key: Key, from: SeqNr) = {
        val stream = StateT { state =>
          val stream = Stream[StateT].apply(state.events)
          val read = Read(key, from)
          val state1 = state.copy(reads = read :: state.reads, events = Nil)
          (state1, stream)
        }
        Stream.lift(stream).flatten
      }

      def pointer(key: Key) = {
        StateT { state =>
          val state1 = state.copy(pointers = Pointer(key) :: state.pointers)
          (state1, none[SeqNr])
        }
      }

      def delete(key: Key, to: SeqNr) = {
        StateT { state =>
          val delete = Delete(key, to, timestamp)
          val state1 = state.copy(deletes = delete :: state.deletes)
          (state1, none[PartitionOffset])
        }
      }

      def purge(key: Key) = {
        StateT { state =>
          val state1 = state.append(Action.Purge(key))
          (state1, none[PartitionOffset])
        }
      }
    }

    def apply[A](f: State => (State, A)): StateT[A] = cats.data.StateT[Id, State, A](f)
  }
}
