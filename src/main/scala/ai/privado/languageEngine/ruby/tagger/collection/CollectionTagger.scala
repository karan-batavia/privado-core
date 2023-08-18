package ai.privado.languageEngine.ruby.tagger.collection

import ai.privado.cache.RuleCache
import ai.privado.model.{Constants, InternalTag, RuleInfo}
import ai.privado.tagger.PrivadoParallelCpgPass
import ai.privado.utility.Utilities._
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{Call, Method}
import io.shiftleft.semanticcpg.language._
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

class CollectionTagger(cpg: Cpg, ruleCache: RuleCache) extends PrivadoParallelCpgPass[RuleInfo](cpg) {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val methodUrlMap = mutable.HashMap[Long, String]()
  private val classUrlMap = mutable.HashMap[Long, String]()

  override def generateParts(): Array[RuleInfo] =
    ruleCache.getRule.collections.filter(_.catLevelTwo == Constants.default).toArray

  override def runOnPart(builder: DiffGraphBuilder, collectionRuleInfo: RuleInfo): Unit = {

    val collectionMethods = cpg.call("get|post|put|patch|delete").where(_.file.name(".*routes.rb")).filter(_.argument.isCall.code("to.*").argument.isLiteral.nonEmpty).l

    /*
    creating map having key as method Id which is getting invoked by collection and value as url
    */
    val collectionMethodsCache = collectionMethods
      .map { m =>
        //        sample route -> get '/hotels/new', to: 'hotels#new'
        //TODO: check scenarios involving single, double and no quote
        println(m.code)
        val targetCollectionUrl = m.argument.isCall.code("to:.*").argument.isLiteral.code.l
//        if (targetCollectionUrl.isEmpty) {
//          contin
//        }
        if (!targetCollectionUrl.isEmpty) {
          val routeMethodCall: String = targetCollectionUrl.head.stripPrefix("\"").stripSuffix("\"")
          val routeSeparatedStrings = routeMethodCall.split("#")
          val methodName = routeSeparatedStrings(1)
          val fileName = ".*" + routeSeparatedStrings(0) + ".*"
          if (!cpg.method.name(methodName).where(_.file.name(fileName)).l.isEmpty) {
            methodUrlMap.addOne(
              // we only get methodFullName here from the call node, so we have to get the relevant method for key
              cpg.method
                .name(methodName)
                .where(_.file.name(fileName)).l.head.id() -> m.argument.isLiteral.code.head
              //          TODO: use .stripPrefix("\"").stripSuffix("\"") if required
            )
            cpg.method.name(methodName).where(_.file.name(fileName)).l
          } else {
            List()
          }
        }
        else {
          List()
        }
      }
      .l
      .flatten(method => method) // returns the handler method list

    tagDirectSources(cpg, builder, collectionMethodsCache.l, collectionRuleInfo)
  }

  def tagDirectSources(
                        cpg: Cpg,
                        builder: DiffGraphBuilder,
                        collectionMethods: List[Method],
                        collectionRuleInfo: RuleInfo
                      ): Unit = {
    val collectionPoints = collectionMethods.flatMap(collectionMethod => {
      ruleCache.getRule.sources.flatMap(sourceRule => {
        val parameters = collectionMethod.parameter
        val locals = collectionMethod.local
        val literals = collectionMethod.literal

        // TODO: handle cases where `request.args.get('id', None)` used directly in handler block without method param
        val matchingParameters = parameters.where(_.name(sourceRule.combinedRulePattern)).whereNot(_.code("self")).l
        val matchingLocals = locals.code(sourceRule.combinedRulePattern).l
        val matchingLiterals = literals
          .code(sourceRule.combinedRulePattern)
          .l

        if (!(matchingParameters.isEmpty && matchingLocals.isEmpty && matchingLiterals.isEmpty)) {
          matchingParameters.foreach(parameter =>
            storeForTag(builder, parameter, ruleCache)(Constants.id, sourceRule.id)
          )
          matchingLocals.foreach(local => storeForTag(builder, local, ruleCache)(Constants.id, sourceRule.id))
          matchingLiterals.foreach(literal => storeForTag(builder, literal, ruleCache)(Constants.id, sourceRule.id))
          Some(collectionMethod)
        } else {
          None
        }

      })
    })

    tagMethodEndpoints(builder, collectionPoints.l, collectionRuleInfo)
  }

  private def tagMethodEndpoints(
                                  builder: DiffGraphBuilder,
                                  collectionPoints: List[Method],
                                  collectionRuleInfo: RuleInfo,
                                  returnByName: Boolean = false
                                ) = {
    collectionPoints.foreach(collectionPoint => {
      addRuleTags(builder, collectionPoint, collectionRuleInfo, ruleCache)
      storeForTag(builder, collectionPoint, ruleCache)(
        InternalTag.COLLECTION_METHOD_ENDPOINT.toString,
        getFinalEndPoint(collectionPoint, returnByName)
      )
    })
  }

  private def getFinalEndPoint(collectionPoint: Method, returnByName: Boolean): String = {
    if (returnByName) {
      collectionPoint.name
    } else {
      val methodUrl = methodUrlMap.getOrElse(collectionPoint.id(), "")
      Try(classUrlMap.getOrElse(collectionPoint.typeDecl.head.id(), "")) match {
        case Success(classUrl) => classUrl + methodUrl
        case Failure(e) =>
          methodUrl
      }
    }
  }

}
