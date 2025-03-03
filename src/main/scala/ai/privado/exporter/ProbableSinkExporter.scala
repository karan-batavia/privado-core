package ai.privado.exporter

import ai.privado.cache.RuleCache
import ai.privado.metric.MetricHandler
import ai.privado.model.{CatLevelOne, Constants}
import ai.privado.utility.Utilities.{getAllFilesRecursively, getAllFilesRecursivelyWithoutExtension, isPrivacySink}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.Languages
import io.shiftleft.semanticcpg.language._
import org.slf4j.LoggerFactory
import io.circe.parser.{parse, _}
import io.circe._

class ProbableSinkExporter(cpg: Cpg, ruleCache: RuleCache, repoPath: String) {
  private val logger = LoggerFactory.getLogger(getClass)

  def getProbableSinks: List[String] = {

    val lang         = MetricHandler.metricsData("language")
    val isPython     = lang.toString().contains(Languages.PYTHONSRC)
    val isJavascript = lang.toString().contains(Languages.JSSRC)
    val isRuby       = lang.toString().contains(Languages.RUBYSRC)

    if (isJavascript) {
      getProbableSinkForJavascript(repoPath)
    } else if (isRuby) {
      getProbableSinkForRuby(repoPath)
    } else {
      getProbableSinkBasedOnTaggedMethods(isPython)
    }
  }

  def getProbableSinkForJavascript(repoPath: String): List[String] = {
    // Set up a set to hold the unique dependencies
    var uniqueDeps = Set.empty[String]
    val packageJsonFilePaths =
      getAllFilesRecursively(repoPath, Set(".json"), ruleCache)
        .getOrElse(List.empty)
        .filter(_.endsWith("package.json"))

    for (path <- packageJsonFilePaths) {
      val packageJsonStr = scala.io.Source.fromFile(path).mkString
      val json           = parse(packageJsonStr).getOrElse(Json.Null)
      val dependencies   = json.hcursor.downField("dependencies").as[Map[String, String]].getOrElse(Map.empty)
      uniqueDeps ++= dependencies.keySet
    }
    uniqueDeps.toList
      .filter((str) => isPrivacySink(str, ruleCache))
  }

  def getProbableSinkForRuby(repoPath: String): List[String] = {
    // Set up a set to hold the unique dependencies
    val gemFilePaths =
      getAllFilesRecursivelyWithoutExtension(repoPath, "Gemfile")
        .getOrElse(List.empty)
        .filter(_.endsWith("Gemfile"))
    val uniqueDependencies = gemFilePaths.flatMap(parseGemfileDependencies).toSet.toList
    uniqueDependencies.filter(isPrivacySink(_, ruleCache))
  }

  def parseGemfileDependencies(filePath: String): List[String] = {
    val gemfileLines = scala.io.Source.fromFile(filePath).getLines().toList
    val gemLines     = gemfileLines.filter(_.startsWith("gem"))

    gemLines.flatMap { line =>
      val gemName = line.stripPrefix("gem").trim().split(",").headOption.map(_.trim())
      gemName.map(_.stripPrefix("'").stripSuffix("'").stripPrefix("\"").stripSuffix("\""))
    }
  }

  def getProbableSinkBasedOnTaggedMethods(isPython: Boolean): List[String] = {

    /** Get all the Methods which are tagged as SINKs */
    val taggedSinkMethods = cpg.tag
      .where(_.nameExact(Constants.catLevelOne).valueExact(CatLevelOne.SINKS.name))
      .call
      .l
      .map(i => {
        var res = i.methodFullName
        if (!isPython) {
          res = res.split(":").headOption.getOrElse("")
        }
        res
      })
      .distinct

    logger.debug("Tagged Sink Methods: ", taggedSinkMethods.length)

    /** Get all the Methods which are external */
    val dependenciesTPs = cpg.method.external.l.map(i => {
      var res = i.fullName
      if (!isPython) {
        res = res.split(":").headOption.getOrElse("")
      }
      res
    })

    logger.debug("Dependencies TPS: ", dependenciesTPs.length)
    logger.debug("Total Methods: ", cpg.method.l.length)

    /** Actions: by excluding taggedSinkMethods check isPrivacySink transform method FullName close to groupIds remove
      * duplicates
      */
    val filteredTPs = dependenciesTPs
      .filter(str => !taggedSinkMethods.contains(str))
      .filter((str) => isPrivacySink(str, ruleCache))
      .filter((str) => !str.endsWith(".println"))
      .map((str) => {
        try {
          str.split("\\.").take(6).mkString(".").split(":").headOption.getOrElse("")
        } catch {
          case _: Exception => str
        }
      })
      .distinct

    logger.debug("Filtered TPS: ", filteredTPs)
    logger.debug("Filtered TPs Count: ", filteredTPs.length)
    filteredTPs
  }

}
