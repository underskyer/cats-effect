/*
 * Copyright 2020-2024 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect.unsafe
package metrics

import scala.concurrent.ExecutionContext

/**
 * Represents metrics associated with a work-stealing thread pool.
 */
sealed trait WorkStealingPoolMetrics {

  /**
   * The identifier of the instrumented work-stealing thread pool. Uniquely identifies a
   * specific thread pool within a JVM.
   */
  def identifier: String

  /**
   * The number of worker thread instances backing the work-stealing thread pool (WSTP).
   *
   * @note
   *   this is a fixed value, as the WSTP has a fixed number of worker threads.
   */
  def workerThreadCount(): Int

  /**
   * The number of active worker thread instances currently executing fibers on the compute
   * thread pool.
   *
   * @note
   *   the value may differ between invocations
   */
  def activeThreadCount(): Int

  /**
   * The number of worker thread instances currently searching for fibers to steal from other
   * worker threads.
   *
   * @note
   *   the value may differ between invocations
   */
  def searchingThreadCount(): Int

  /**
   * The number of worker thread instances that can run blocking actions on the compute thread
   * pool.
   *
   * @note
   *   the value may differ between invocations
   */
  def blockedWorkerThreadCount(): Int

  /**
   * The total number of fibers enqueued on all local queues.
   *
   * @note
   *   the value may differ between invocations
   */
  def localQueueFiberCount(): Long

  /**
   * The number of fibers which are currently asynchronously suspended.
   *
   * @note
   *   This counter is not synchronized due to performance reasons and might be reporting
   *   out-of-date numbers.
   *
   * @note
   *   the value may differ between invocations
   */
  def suspendedFiberCount(): Long

  /**
   * The list of worker-specific metrics of this work-stealing thread pool.
   */
  def workerThreads: List[WorkerThreadMetrics]
}

sealed trait WorkerThreadMetrics {

  /**
   * The index of this WorkerThread.
   */
  def index: Int

  /**
   * LocalQueue-specific metrics of this WorkerThread.
   */
  def localQueue: LocalQueueMetrics

  /**
   * TimerHeap-specific metrics of this WorkerThread.
   */
  def timerHeap: TimerHeapMetrics
}

/**
 * The metrics of the local queue.
 */
sealed trait LocalQueueMetrics {

  /**
   * The current number of enqueued fibers.
   *
   * @note
   *   the value may differ between invocations
   */
  def fiberCount(): Int

  /**
   * The total number of fibers enqueued during the lifetime of the local queue.
   *
   * @note
   *   the value may differ between invocations
   */
  def totalFiberCount(): Long

  /**
   * The total number of fibers spilt over to the external queue.
   *
   * @note
   *   the value may differ between invocations
   */
  def totalSpilloverCount(): Long

  /**
   * The total number of successful steal attempts by other worker threads.
   *
   * @note
   *   the value may differ between invocations
   */
  def successfulStealAttemptCount(): Long

  /**
   * The total number of stolen fibers by other worker threads.
   *
   * @note
   *   the value may differ between invocations
   */
  def stolenFiberCount(): Long

}

sealed trait TimerHeapMetrics {

  /**
   * The current number of the outstanding timers, that remain to be executed.
   *
   * @note
   *   the value may differ between invocations
   */
  def timersOutstandingCount(): Int

  /**
   * The total number of the successfully executed timers.
   *
   * @note
   *   the value may differ between invocations
   */
  def totalTimersExecutedCount(): Long

  /**
   * The total number of the scheduled timers.
   *
   * @note
   *   the value may differ between invocations
   */
  def totalTimersScheduledCount(): Long

  /**
   * The total number of the canceled timers.
   *
   * @note
   *   the value may differ between invocations
   */
  def totalTimersCanceledCount(): Long

  /**
   * Returns the time in nanoseconds till the next due to fire.
   *
   * The negative number could indicate that the worker thread is overwhelmed by (long-running)
   * tasks and not able to check/trigger timers frequently enough. The indication is similar to
   * the starvation checker.
   *
   * Returns `None` when there is no upcoming timer.
   *
   * @note
   *   the value may differ between invocations
   */
  def nextTimerDue(): Option[Long]

  /**
   * Returns the total number of times the heap packed itself to remove canceled timers.
   */
  def packCount(): Long

}

object WorkStealingPoolMetrics {

  private[metrics] def apply(ec: ExecutionContext): Option[WorkStealingPoolMetrics] =
    ec match {
      case wstp: WorkStealingThreadPool[_] => Some(workStealingThreadPoolMetrics(wstp))
      case _ => None
    }

  private def workStealingThreadPoolMetrics(
      wstp: WorkStealingThreadPool[_]
  ): WorkStealingPoolMetrics = new WorkStealingPoolMetrics {
    val identifier =
      wstp.id.toString

    def workerThreadCount(): Int = wstp.getWorkerThreadCount()
    def activeThreadCount(): Int = wstp.getActiveThreadCount()
    def searchingThreadCount(): Int = wstp.getSearchingThreadCount()
    def blockedWorkerThreadCount(): Int = wstp.getBlockedWorkerThreadCount()
    def localQueueFiberCount(): Long = wstp.getLocalQueueFiberCount()
    def suspendedFiberCount(): Long = wstp.getSuspendedFiberCount()

    val workerThreads: List[WorkerThreadMetrics] =
      List.range(0, workerThreadCount()).map(workerThreadMetrics(wstp, _))
  }

  private def workerThreadMetrics(
      wstp: WorkStealingThreadPool[_],
      idx: Int
  ): WorkerThreadMetrics = new WorkerThreadMetrics {
    val index: Int = idx
    val localQueue: LocalQueueMetrics = localQueueMetrics(wstp.localQueues(index))
    val timerHeap: TimerHeapMetrics = timerHeapMetrics(wstp.sleepers(index))
  }

  private def localQueueMetrics(queue: LocalQueue): LocalQueueMetrics =
    new LocalQueueMetrics {
      def fiberCount(): Int = queue.getFiberCount()
      def totalFiberCount(): Long = queue.getTotalFiberCount()
      def totalSpilloverCount(): Long = queue.getTotalSpilloverCount()
      def successfulStealAttemptCount(): Long = queue.getSuccessfulStealAttemptCount()
      def stolenFiberCount(): Long = queue.getStolenFiberCount()
    }

  private def timerHeapMetrics(timerHeap: TimerHeap): TimerHeapMetrics =
    new TimerHeapMetrics {
      def nextTimerDue(): Option[Long] = timerHeap.nextTimerDue()
      def timersOutstandingCount(): Int = timerHeap.outstandingTimers()
      def totalTimersExecutedCount(): Long = timerHeap.totalTimersExecuted()
      def totalTimersScheduledCount(): Long = timerHeap.totalTimersScheduled()
      def totalTimersCanceledCount(): Long = timerHeap.totalTimersCanceled()
      def packCount(): Long = timerHeap.packCount()
    }
}
