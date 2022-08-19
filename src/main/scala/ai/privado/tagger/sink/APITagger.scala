package ai.privado.tagger.sink

import ai.privado.language._
import ai.privado.model.Constants
import ai.privado.semantic.Language._
import ai.privado.tagger.PrivadoSimplePass
import ai.privado.utility.Utilities.{addRuleTags, storeForTag}
import io.joern.dataflowengineoss.language._
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, nodes}
import io.shiftleft.codepropertygraph.generated.nodes.{CfgNode, JavaProperty}
import io.shiftleft.semanticcpg.language._
import overflowdb.BatchedUpdate

class APITagger(cpg: Cpg) extends PrivadoSimplePass(cpg) {

  lazy val cacheCall = cpg.call.where(_.nameNot("(<operator|<init).*")).l

  lazy val APISINKS_REGEX =
    "(?i)(?:url|client|openConnection|request|execute|newCall|load|host|access|fetch|get|getInputStream|getApod|getForObject|list|set|put|post|proceed|trace|patch|Path|send|sendAsync|remove|delete|write|read|assignment|provider)"
  override def run(builder: BatchedUpdate.DiffGraphBuilder): Unit = {
    val apiInternalSinkPattern = cpg.literal.code("\"(" + ruleInfo.patterns.head + ")\"").l
    val apis                   = cacheCall.name(APISINKS_REGEX).l
    sinkTagger(apiInternalSinkPattern, apis, builder)
    val propertySinks = cpg.property.filter(p => p.value matches (ruleInfo.patterns.head)).usedAt.l
    sinkTagger(propertySinks, apis, builder)
  }
  private def sinkTagger(
    apiInternalSinkPattern: List[CfgNode],
    apis: List[CfgNode],
    builder: BatchedUpdate.DiffGraphBuilder
  ): Unit = {
    if (apis.nonEmpty && apiInternalSinkPattern.nonEmpty) {
      val apiFlows = apis.reachableByFlows(apiInternalSinkPattern).l
      apiFlows.foreach(flow => {
        var literalCode = flow.elements.head.code
        val property    = flow.elements.head.out(EdgeTypes.ORIGINAL_PROPERTY)
        if (property != null && property.hasNext) {
          val next = property.next()
          if (next.isInstanceOf[JavaProperty]) {
            literalCode = next.asInstanceOf[JavaProperty].value
          }
        }
        val apiNode = flow.elements.last
        addRuleTags(builder, apiNode, ruleInfo)
        storeForTag(builder, apiNode)(Constants.apiUrl, literalCode)
      })
    }
  }
}
