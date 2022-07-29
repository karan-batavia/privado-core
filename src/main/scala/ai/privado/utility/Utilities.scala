package ai.privado.utility

import ai.privado.model.CatLevelOne.CatLevelOne
import ai.privado.model.{Constants, RuleInfo}
import io.joern.dataflowengineoss.semanticsloader.{Parser, Semantics}
import io.shiftleft.codepropertygraph.generated.EdgeTypes
import io.shiftleft.codepropertygraph.generated.nodes.NewTag
import io.shiftleft.utils.IOUtils
import org.slf4j.LoggerFactory
import overflowdb.{BatchedUpdate, NodeOrDetachedNode}

import java.nio.file.Paths
import scala.io.Source
import scala.util.Try

object Utilities {

  val logger = LoggerFactory.getLogger(getClass)

  /*
   Utility to add a single tag to a object
   */
  def storeForTag(
    builder: BatchedUpdate.DiffGraphBuilder,
    source: NodeOrDetachedNode
  )(tagName: String, tagValue: String = "") = {
    builder.addEdge(source, NewTag().name(tagName).value(tagValue), EdgeTypes.TAGGED_BY)
  }

  /*
   Utility to add Tag based on a rule Object
   */
  def addRuleTags(builder: BatchedUpdate.DiffGraphBuilder, node: NodeOrDetachedNode, ruleInfo: RuleInfo): Unit = {
    val storeForTagHelper = storeForTag(builder, node) _
    storeForTagHelper(Constants.id, ruleInfo.id)
    storeForTagHelper(Constants.nodeType, ruleInfo.nodeType.toString)
    storeForTagHelper(Constants.catLevelOne, ruleInfo.catLevelOne.name)
    storeForTagHelper(Constants.catLevelTwo, ruleInfo.catLevelTwo)

    for ((key, value) <- ruleInfo.tags) {
      storeForTagHelper(key, value)
    }
  }

  /*
   Utility to get the default semantics for dataflow queries
   */
  def getDefaultSemantics() = {
    val semanticsFilename = Source.fromResource("default.semantics")
    Semantics.fromList(new Parser().parse(semanticsFilename.getLines().mkString("")))
  }

  /*
   Utility to filter rules by catLevelOne
   */
  def getRulesByCatLevelOne(rules: List[RuleInfo], catLevelOne: CatLevelOne) =
    rules.filter(rule => rule.catLevelOne.equals(catLevelOne))

  /** For a given `filename`, `lineToHighlight`, return the corresponding code by reading it from the file. If
    * `lineToHighlight` is defined, then a line containing an arrow (as a source code comment) is included right before
    * that line.
    */
  def dump(filename: String, lineToHighlight: Option[Integer]): String = {
    val arrow: CharSequence = "/* <=== */ "
    val lines = Try(IOUtils.readLinesInFile(Paths.get(filename))).getOrElse {
      logger.error("Error reading from file : " + filename);
      List()
    }
    val startLine: Integer = {
      if (lineToHighlight.isDefined)
        Math.max(0, lineToHighlight.get - 5)
      else
        0
    }
    val endLine: Integer = {
      if (lineToHighlight.isDefined)
        Math.min(lines.length, lineToHighlight.get + 5)
      else
        0
    }
    lines
      .slice(startLine - 1, endLine)
      .zipWithIndex
      .map { case (line, lineNo) =>
        if (lineToHighlight.isDefined && lineNo == lineToHighlight.get - startLine) {
          line + " " + arrow
        } else {
          line
        }
      }
      .mkString("\n")
  }
}
