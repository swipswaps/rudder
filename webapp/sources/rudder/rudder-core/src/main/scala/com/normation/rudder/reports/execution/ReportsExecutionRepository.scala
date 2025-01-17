/*
*************************************************************************************
* Copyright 2013 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.reports.execution

import com.normation.inventory.domain.NodeId
import net.liftweb.common._
import com.normation.rudder.repository.CachedRepository
import com.normation.rudder.domain.logger.{ReportLogger, TimingDebugLogger}
import com.normation.rudder.domain.reports.NodeAndConfigId
import com.normation.rudder.repository.FindExpectedReportRepository
import zio._
import com.normation.zio._
import com.normation.errors._

/**
 * Service for reading or storing execution of Nodes
 */
trait RoReportsExecutionRepository {

  /**
   * Find the last execution of nodes, whatever is its state.
   * Last execution is defined as "the last executions that have been inserted in the database",
   * and do not rely on date (which can change too often)
   * The goal is to have reporting that does not depend on time, as node may have time in the future, or
   * past, or even change during their lifetime
   * So the last run are the last run inserted in the reports database
   * See ticket http://www.rudder-project.org/redmine/issues/6005
   */
  def getNodesLastRun(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[AgentRunWithNodeConfig]]]
}


trait WoReportsExecutionRepository {

  /**
   * Create or update the list of execution in the execution tables
   * Only return execution which where actually changed in backend
   *
   * The logic is:
   * - a new execution (not present in backend) is inserted as provided
   * - a existing execution can only change the completion status from
   *   "not completed" to "completed" (i.e: a completed execution can
   *   not be un-completed).
   */
  def updateExecutions(executions : Seq[AgentRun]) : Seq[Box[AgentRun]]

}

/**
 * A cached version of the service that only look in the underlying data (expected to
 * be slow) when no cache available.
 */
class CachedReportsExecutionRepository(
    readBackend : RoReportsExecutionRepository
  , writeBackend: WoReportsExecutionRepository
  , findConfigs : FindExpectedReportRepository
) extends RoReportsExecutionRepository with WoReportsExecutionRepository with CachedRepository {

  val logger = ReportLogger
  val semaphore = Semaphore.make(1).runNow

  /*
   * We need to synchronise on cache to avoid the case:
   * - initial state: RUNS_0 in backend
   * - write RUNS_1 (cache => None) : ok, only write in backend
   * [interrupt before actual write]
   * - read: cache = None => update cache with RUNS_0
   * - cache = RUNS_0
   * [resume write]
   * - write in backend RUNS_1
   *
   * => cache will never ses RUNS_1 before a clear.
   */

  /*
   * The cache is managed node by node, i.e it can be initialized
   * for certain and not for other.
   * The initialization criteria (and so, the fact that the cache
   * can be used for a given node) is given by the presence of the
   * nodeid in map's keys.
   */
  private[this] var cache = Map[NodeId, Option[AgentRunWithNodeConfig]]()


  override def clearCache(): Unit = semaphore.withPermit(IOResult.effect {
    cache = Map()
  }).runNow

  override def getNodesLastRun(nodeIds: Set[NodeId]): Box[Map[NodeId, Option[AgentRunWithNodeConfig]]] = semaphore.withPermit(IOResult.effect {
    val n1 = System.currentTimeMillis
    (for {
      runs <- readBackend.getNodesLastRun(nodeIds.diff(cache.keySet))
    } yield {
      val n2 = System.currentTimeMillis
      TimingDebugLogger.trace(s"CachedReportsExecutionRepository: get nodes last run in: ${n2 - n1}ms")
      cache = cache ++ runs
      cache.filterKeys { x => nodeIds.contains(x) }
    }) ?~! s"Error when trying to update the cache of Agent Runs informations"
  }).runNow

  override def updateExecutions(executions : Seq[AgentRun]) : Seq[Box[AgentRun]] = semaphore.withPermit(IOResult.effect {
    logger.trace(s"Update runs for nodes [${executions.map( _.agentRunId.nodeId.value ).mkString(", ")}]")
    val n1 = System.currentTimeMillis
    val runs = writeBackend.updateExecutions(executions)
    //update complete runs
    val completed = runs.collect { case Full(x) if(x.isCompleted) => x }
    logger.debug(s"Updating agent runs cache: [${completed.map(x => s"'${x.agentRunId.nodeId.value}' at '${x.agentRunId.date.toString()}'").mkString("," )}]")

    // log errors
    runs.foreach {
      case Full(x) => //
      case eb:EmptyBox =>
        val e = eb ?~! "Error when updating node run information"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach(ex => logger.error(s"Exception was: ${ex.getMessage}"))
    }

    //we need to get NodeExpectedReports for new runs or runs with a different nodeConfigId
    //so we complete run with existing node config in cache, and
    //separate between the one completed and the one to query
    // the one to query are on left, the one completed on right
    val runWithConfigs = completed.map { x =>
      x.nodeConfigVersion match {
        case None            => //no need to try more things
          Right(AgentRunWithNodeConfig(x.agentRunId, None, x.isCompleted, x.insertionId))
        case Some(newConfig) =>
          cache.get(x.agentRunId.nodeId) match {
            case Some(Some(AgentRunWithNodeConfig(_, Some((oldConfig, Some(expected))), _, _))) if(oldConfig == newConfig) =>
                Right(AgentRunWithNodeConfig(x.agentRunId, Some((oldConfig, Some(expected))), x.isCompleted, x.insertionId))
            case _ =>
                Left(AgentRunWithNodeConfig(x.agentRunId, x.nodeConfigVersion.map(c => (c, None)), x.isCompleted, x.insertionId))
          }
      }
    }
    val n2 = System.currentTimeMillis
    TimingDebugLogger.trace(s"CachedReportsExecutionRepository: update execution in ${n2 - n1}ms")

    //now, get back the one in left, and query for node config
    val nodeAndConfigs = runWithConfigs.collect { case Left(AgentRunWithNodeConfig(id, Some((config, None)), _, _)) => NodeAndConfigId(id.nodeId, config)}

    val results = (findConfigs.getExpectedReports(nodeAndConfigs.toSet) match {
      case eb: EmptyBox =>
        val e = eb ?~! s"Error when trying to find node configuration matching new runs for node/configId: ${nodeAndConfigs.map(x=> s"${x.nodeId.value}/${x.version.value}").mkString(", ")}"
        logger.error(e.messageChain)
        //return the whole list of runs unmodified
        runWithConfigs.map {
          case Left(x)  => (x.agentRunId.nodeId, Some(x))
          case Right(x) => (x.agentRunId.nodeId, Some(x))
        }
      case Full(map) =>
        runWithConfigs.map {
          case Left(x)  =>
            val configVersion = x.nodeConfigVersion.map(c => c.copy(_2 = map.get(NodeAndConfigId(x.agentRunId.nodeId, c._1)).flatten))
            (x.agentRunId.nodeId, Some(x.copy(nodeConfigVersion = configVersion)))
          case Right(x) => (x.agentRunId.nodeId, Some(x))
        }
    }).toMap
    val n3 = System.currentTimeMillis
    TimingDebugLogger.trace(s"CachedReportsExecutionRepository: compte result of update execution in ${n3 - n2}ms")

    cache = cache ++ results
    runs
  }).runNow
}
