package akka.persistence.kafka.journal

import akka.actor.ActorSystem
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.{AtomicWrite, PersistentRepr}
import cats.effect.{ContextShift, IO, Resource, Timer}
import com.evolutiongaming.concurrent.async.Async
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.eventual.EventualJournal
import com.evolutiongaming.kafka.journal.util.FromFuture
import com.evolutiongaming.safeakka.actor.ActorLog
import com.evolutiongaming.skafka.ClientId
import com.evolutiongaming.skafka.consumer.Consumer
import com.evolutiongaming.skafka.producer.Producer
import com.typesafe.config.Config

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Try
import scala.util.control.NonFatal

class KafkaJournal(config: Config) extends AsyncWriteJournal {
  import KafkaJournal._

  implicit val system: ActorSystem = context.system
  implicit val ec: ExecutionContextExecutor = context.dispatcher
  implicit val cs: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: Timer[IO] = IO.timer(ec)
  implicit val fromFuture: FromFuture[IO] = FromFuture.lift[IO]

  val log: ActorLog = ActorLog(system, classOf[KafkaJournal])

  lazy val (adapter, release): (JournalAdapter, IO[Unit]) = {
    val adapter = adapterOf(
      toKey(),
      origin(),
      serializer(),
      kafkaJournalConfig(),
      metrics())

    adapter.allocated.unsafeRunSync()
  }

  override def preStart(): Unit = {
    super.preStart()
    adapter
  }

  override def postStop(): Unit = {
    try release.unsafeRunSync() catch {
      case NonFatal(failure) => log.error(s"release failed with $failure", failure)
    }
    super.postStop()
  }


  def toKey(): ToKey = ToKey(config)

  def kafkaJournalConfig() = KafkaJournalConfig(config)

  def origin(): Option[Origin] = Some {
    Origin.HostName orElse Origin.AkkaHost(system) getOrElse Origin.AkkaName(system)
  }

  def serializer(): EventSerializer = EventSerializer(system)

  def metrics(): Metrics = Metrics.Empty

  def adapterOf(
    toKey: ToKey,
    origin: Option[Origin],
    serializer: EventSerializer,
    config: KafkaJournalConfig,
    metrics: Metrics): Resource[IO, JournalAdapter] = {

    log.debug(s"Config: $config")

    JournalAdapter.adapterOf[IO](toKey, origin, serializer, config, metrics, log)
  }

  // TODO optimise concurrent calls asyncReplayMessages & asyncReadHighestSequenceNr for the same persistenceId
  def asyncWriteMessages(atomicWrites: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = {
    adapter.write(atomicWrites)
  }

  def asyncDeleteMessagesTo(persistenceId: PersistenceId, to: Long): Future[Unit] = {
    SeqNr.opt(to) match {
      case Some(to) => adapter.delete(persistenceId, to)
      case None     => Future.unit
    }
  }

  def asyncReplayMessages(persistenceId: PersistenceId, from: Long, to: Long, max: Long)
    (f: PersistentRepr => Unit): Future[Unit] = {

    val seqNrFrom = SeqNr(from, SeqNr.Min)
    val seqNrTo = SeqNr(to, SeqNr.Max)
    val range = SeqRange(seqNrFrom, seqNrTo)
    adapter.replay(persistenceId, range, max)(f)
  }

  def asyncReadHighestSequenceNr(persistenceId: PersistenceId, from: Long): Future[Long] = {
    val seqNr = SeqNr(from, SeqNr.Min)
    for {
      seqNr <- adapter.lastSeqNr(persistenceId, seqNr)
    } yield seqNr match {
      case Some(seqNr) => seqNr.value
      case None        => from
    }
  }
}

object KafkaJournal {

  final case class Metrics(
    journal: Option[Journal.Metrics[Async]] = None,
    eventual: Option[EventualJournal.Metrics[Async]] = None,
    producer: Option[ClientId => Producer.Metrics] = None,
    consumer: Option[ClientId => Consumer.Metrics] = None)

  object Metrics {
    val Empty: Metrics = Metrics()
  }
}