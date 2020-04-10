package com.evolutiongaming.kafka.journal

import java.time.Instant

import cats._
import cats.implicits._
import com.evolutiongaming.skafka.{Offset, Partition}

/**
 * @param origin identifier of event origin, for instance node IP address
 */
final case class EventRecord[A](
  event: Event[A],
  timestamp: Instant,
  partitionOffset: PartitionOffset,
  origin: Option[Origin] = None,
  metadata: RecordMetadata,
  headers: Headers
) {

  def seqNr: SeqNr = event.seqNr

  def offset: Offset = partitionOffset.offset

  def partition: Partition = partitionOffset.partition

  def pointer: JournalPointer = JournalPointer(partitionOffset, event.seqNr)
}

object EventRecord {

  def apply[A](
    record: ActionRecord[Action.Append],
    event: Event[A],
    metadata: PayloadMetadata
  ): EventRecord[A] = {
    apply(record.action, event, record.partitionOffset, metadata)
  }

  def apply[A](
    action: Action.Append,
    event: Event[A],
    partitionOffset: PartitionOffset,
    metadata: PayloadMetadata,
  ): EventRecord[A] = {
    EventRecord(
      event = event,
      timestamp = action.timestamp,
      partitionOffset = partitionOffset,
      origin = action.origin,
      metadata = RecordMetadata(
        header = action.header.metadata,
        payload = metadata),
      headers = action.headers)
  }

  implicit val traverse: Traverse[EventRecord] = new Traverse[EventRecord] {
    override def traverse[G[_] : Applicative, A, B](fa: EventRecord[A])(f: A => G[B]): G[EventRecord[B]] =
      fa.event.traverse(f).map(e => fa.copy(event = e))

    override def foldLeft[A, B](fa: EventRecord[A], b: B)(f: (B, A) => B): B =
      fa.event.foldLeft(b)(f)

    override def foldRight[A, B](fa: EventRecord[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      fa.event.foldRight(lb)(f)
  }
}