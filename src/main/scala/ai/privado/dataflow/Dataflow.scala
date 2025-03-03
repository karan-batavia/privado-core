/*
 * This file is part of Privado OSS.
 *
 * Privado is an open source static code analysis tool to discover data flows in the code.
 * Copyright (C) 2022 Privado, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, contact support@privado.ai
 *
 */

package ai.privado.dataflow

import ai.privado.audit.UnresolvedFlowReport
import ai.privado.cache.{AppCache, AuditCache, DataFlowCache, RuleCache}
import ai.privado.dataflow.Dataflow.getExpendedFlowInfo
import ai.privado.entrypoint.{PrivadoInput, ScanProcessor, TimeMetric}
import ai.privado.exporter.ExporterUtility
import ai.privado.languageEngine.java.semantic.JavaSemanticGenerator
import ai.privado.languageEngine.javascript.JavascriptSemanticGenerator
import ai.privado.languageEngine.python.semantic.PythonSemanticGenerator
import ai.privado.model.{CatLevelOne, Constants, InternalTag, Language}
import io.joern.dataflowengineoss.language.*
import io.joern.dataflowengineoss.queryengine.{EngineConfig, EngineContext}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{AstNode, Call, CfgNode}
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory
import overflowdb.traversal.Traversal
import ai.privado.model.exporter.DataFlowPathIntermediateModel
import ai.privado.utility.Utilities
import io.joern.dataflowengineoss.semanticsloader.Semantics

import java.util.Calendar
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success}

class Dataflow(cpg: Cpg) {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Compute the flow of data from tagged Sources to Sinks
    *
    * @return
    *   \- Map of PathId -> Path corresponding to source to sink path
    */
  def dataflow(
    privadoScanConfig: PrivadoInput,
    ruleCache: RuleCache,
    dataFlowCache: DataFlowCache,
    auditCache: AuditCache
  ): Map[String, Path] = {

    if (privadoScanConfig.generateAuditReport && privadoScanConfig.enableAuditSemanticsFilter) {
      auditCache.addIntoBeforeSemantics(cpg, privadoScanConfig, ruleCache)
    }

    logger.info("Generating dataflow")
    implicit val engineContext: EngineContext =
      Utilities.getEngineContext(4)(semanticsP = getSemantics(cpg, privadoScanConfig, ruleCache))

    val sources = Dataflow.getSources(cpg)
    var sinks   = Dataflow.getSinks(cpg)

    println(s"${TimeMetric.getNewTimeAndSetItToStageLast()} - --no of source nodes - ${sources.size}")
    println(s"${TimeMetric.getNewTimeAndSetItToStageLast()} - --no of sinks nodes - ${sinks.size}")

    if (privadoScanConfig.limitNoSinksForDataflows > -1) {
      sinks = sinks.take(privadoScanConfig.limitNoSinksForDataflows)
      println(s"${TimeMetric.getNewTimeAndSetItToStageLast()} - --no of sinks nodes post limit - ${sinks.size}")
    }

    if (sources.isEmpty || sinks.isEmpty) Map[String, Path]()
    else {
      println(s"${TimeMetric.getNewTimeAndSetItToStageLast()} - --Finding flows invoked...")
      val dataflowPathsUnfiltered = {
        if (privadoScanConfig.disable2ndLevelClosure) sinks.reachableByFlows(sources).l
        else {
          val firstLevelSources =
            sources.or(
              _.tag.nameExact(Constants.catLevelOne).valueExact(CatLevelOne.SOURCES.name),
              _.tag.nameExact(InternalTag.OBJECT_OF_SENSITIVE_CLASS_BY_MEMBER_NAME.toString)
            )
          sinks.reachableByFlows(firstLevelSources).l
        }
        // Commented the below piece of code as we still need to test out and fix few open Issues which are
        // resulting in FP in 2nd level derivation for Storages
        /*
        if (privadoScanConfig.disable2ndLevelClosure)
          sinks.reachableByFlows(sources).l
        else {
          // If 2nd level is turned off then dataflows for storages should consider Derived Sources also, but for rest only Sources
          val nonStorageSources =
            sources.or(
              _.tag.nameExact(Constants.catLevelOne).valueExact(CatLevelOne.SOURCES.name),
              _.tag.nameExact(InternalTag.OBJECT_OF_SENSITIVE_CLASS_BY_MEMBER_NAME.toString)
            )
          val nonStorageSinks = sinks.whereNot(_.tag.nameExact(Constants.catLevelTwo).valueExact(Constants.storages))
          val storageSinks    = sinks.where(_.tag.nameExact(Constants.catLevelTwo).valueExact(Constants.storages))

          val nonStorageFlows = nonStorageSinks.reachableByFlows(nonStorageSources).toSet
          val storageFlows    = storageSinks.reachableByFlows(sources).toSet
          (nonStorageFlows ++ storageFlows).l
        }
         */
      }

      if (privadoScanConfig.generateAuditReport) {
        auditCache.addIntoBeforeFirstFiltering(dataflowPathsUnfiltered, privadoScanConfig, ruleCache)

        // For Unresolved flow sheet
        val unfilteredSinks = UnresolvedFlowReport.getUnresolvedSink(cpg)
        val unresolvedFlows = unfilteredSinks.reachableByFlows(sources).l
        auditCache.setUnfilteredFlow(getExpendedFlowInfo(unresolvedFlows))
      }

      // Storing the pathInfo into dataFlowCache
      if (privadoScanConfig.testOutput || privadoScanConfig.generateAuditReport) {
        dataFlowCache.intermediateDataFlow = getExpendedFlowInfo(dataflowPathsUnfiltered)
      }

      println(s"${TimeMetric.getNewTime()} - --Finding flows is done in \t\t\t- ${TimeMetric
          .setNewTimeToStageLastAndGetTimeDiff()} - Unique flows - ${dataflowPathsUnfiltered.size}")
      println(s"${Calendar.getInstance().getTime} - --Filtering flows 1 invoked...")
      AppCache.totalFlowFromReachableBy = dataflowPathsUnfiltered.size

      // Apply `this` filtering for JS & JAVA also
      val dataflowPaths = {
        if (
          privadoScanConfig.disableThisFiltering || (AppCache.repoLanguage != Language.JAVA && AppCache.repoLanguage != Language.JAVASCRIPT)
        )
          dataflowPathsUnfiltered
        else
          dataflowPathsUnfiltered
            .filter(DuplicateFlowProcessor.filterFlowsByContext)
            .filter(DuplicateFlowProcessor.flowNotTaintedByThis)
      }
      println(s"${TimeMetric.getNewTime()} - --Filtering flows 1 is done in \t\t\t- ${TimeMetric
          .setNewTimeToStageLastAndGetTimeDiff()} - Unique flows - ${dataflowPaths.size}")
      AppCache.totalFlowAfterThisFiltering = dataflowPaths.size
      // Stores key -> PathID, value -> Path
      val dataflowMapByPathId = dataflowPaths
        .flatMap(dataflow => {
          DuplicateFlowProcessor.calculatePathId(dataflow) match {
            case Success(pathId) => Some(pathId, dataflow)
            case Failure(e) =>
              logger.debug("Exception : ", e)
              None
          }
        })
        .toMap

      // Setting cache
      dataflowMapByPathId.foreach(item => {
        dataFlowCache.dataflowsMapByType.put(item._1, item._2)
      })

      println(s"${Calendar.getInstance().getTime} - --Filtering flows 2 is invoked...")
      DuplicateFlowProcessor.filterIrrelevantFlowsAndStoreInCache(
        dataflowMapByPathId,
        privadoScanConfig,
        ruleCache,
        dataFlowCache,
        auditCache
      )
      println(
        s"${TimeMetric.getNewTime()} - --Filtering flows 2 is done in \t\t\t- ${TimeMetric
            .setNewTimeToStageLastAndGetTimeDiff()} - Final flows - ${dataFlowCache.dataflow.values.flatMap(_.values).flatten.size}"
      )
    }
    // Need to return the filtered result
    println(s"${Calendar.getInstance().getTime} - --Deduplicating flows invoked...")
    val dataflowFromCache = dataFlowCache.getDataflow
    println(s"${TimeMetric.getNewTime()} - --Deduplicating flows is done in \t\t- ${TimeMetric
        .setNewTimeToStageLastAndGetTimeDiff()} - Unique flows - ${dataflowFromCache.size}")
    auditCache.addIntoFinalPath(dataflowFromCache)
    dataflowFromCache
      .map(_.pathId)
      .toSet
      .map((pathId: String) => (pathId, dataFlowCache.dataflowsMapByType.get(pathId)))
      .toMap
  }

  def getSemantics(cpg: Cpg, privadoScanConfig: PrivadoInput, ruleCache: RuleCache): Semantics = {
    val lang = AppCache.repoLanguage
    lang match {
      case Language.JAVA =>
        JavaSemanticGenerator.getSemantics(cpg, privadoScanConfig, ruleCache, exportRuntimeSemantics = true)
      case Language.PYTHON =>
        PythonSemanticGenerator.getSemantics(cpg, privadoScanConfig, ruleCache, exportRuntimeSemantics = true)
      case Language.JAVASCRIPT =>
        JavascriptSemanticGenerator.getSemantics(cpg, privadoScanConfig, ruleCache, exportRuntimeSemantics = true)
      case _ => JavaSemanticGenerator.getDefaultSemantics
    }
  }

}

object Dataflow {

  def dataflowForSourceSinkPair(sources: List[AstNode], sinks: List[CfgNode]): List[Path] = {
    sinks.reachableByFlows(sources)(Utilities.getEngineContext()).l
  }

  def getSources(cpg: Cpg): List[AstNode] = {
    def filterSources(traversal: Traversal[AstNode]) = {
      traversal.tag
        .nameExact(Constants.catLevelOne)
        .or(_.valueExact(CatLevelOne.SOURCES.name), _.valueExact(CatLevelOne.DERIVED_SOURCES.name))
    }

    cpg.literal
      .where(filterSources)
      .l ++ cpg.identifier
      .where(filterSources)
      .l ++ cpg.call
      .where(filterSources)
      .l ++ cpg.argument.isFieldIdentifier.where(filterSources).l ++ cpg.member.where(filterSources).l
  }

  def getSinks(cpg: Cpg): List[CfgNode] = {
    cpg.call.where(_.tag.nameExact(Constants.catLevelOne).valueExact(CatLevelOne.SINKS.name)).l
  }

  def getExpendedFlowInfo(dataflowPathsUnfiltered: List[Path]): List[DataFlowPathIntermediateModel] = {
    // Fetching the sourceId, sinkId and path Info
    val expendedFlow = ListBuffer[DataFlowPathIntermediateModel]()
    dataflowPathsUnfiltered.map(path => {
      val paths    = path.elements.map(node => ExporterUtility.convertIndividualPathElement(node))
      val pathId   = DuplicateFlowProcessor.calculatePathId(path)
      var sourceId = ""
      if (path.elements.head.tag.nameExact(Constants.catLevelOne).valueExact(CatLevelOne.SOURCES.name).nonEmpty) {
        sourceId = path.elements.head.tag
          .nameExact(Constants.id)
          .valueNot(Constants.privadoDerived + ".*")
          .value
          .headOption
          .getOrElse("")
      } else {
        sourceId = Iterator(path.elements.head).isIdentifier.typeFullName.headOption.getOrElse("")
      }
      var sinkId = path.elements.last.tag.nameExact(Constants.id).value.headOption.getOrElse("")
      // fetch call node methodeFullName if tag not present
      if (sinkId.isEmpty) {
        sinkId = path.elements.last.asInstanceOf[Call].methodFullName
      }
      expendedFlow += DataFlowPathIntermediateModel(sourceId, sinkId, pathId.getOrElse(""), paths)
    })
    expendedFlow.toList
  }
}
