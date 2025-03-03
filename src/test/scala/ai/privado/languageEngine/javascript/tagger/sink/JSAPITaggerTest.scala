package ai.privado.languageEngine.javascript.tagger.sink

import ai.privado.cache.{RuleCache, TaggerCache}
import ai.privado.entrypoint.PrivadoInput
import ai.privado.languageEngine.javascript.tagger.sink.JSAPITagger
import ai.privado.languageEngine.javascript.tagger.source.IdentifierTagger
import ai.privado.model.*
import better.files.File
import io.joern.jssrc2cpg.{Config, JsSrc2Cpg}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable

class JSAPITaggerTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  private val cpgs        = mutable.ArrayBuffer.empty[Cpg]
  private val outPutFiles = mutable.ArrayBuffer.empty[File]
  private val inputDirs   = mutable.ArrayBuffer.empty[File]
  val ruleCache           = new RuleCache()

  val sourceRule = List(
    RuleInfo(
      "Data.Sensitive.FirstName",
      "FirstName",
      "",
      Array(),
      List("(?i).*firstName.*"),
      false,
      "",
      Map(),
      NodeType.REGULAR,
      "",
      CatLevelOne.SOURCES,
      "",
      Language.JAVASCRIPT,
      Array()
    )
  )

  val sinkRule = List(
    RuleInfo(
      Constants.thirdPartiesAPIRuleId,
      "Third Party API",
      "",
      Array(),
      List(
        "((?i)((?:http:|https:|ftp:|ssh:|udp:|wss:){0,1}(\\/){0,2}[a-zA-Z0-9_-][^)\\/(#|,!>\\s]{1,50}\\.(?:com|net|org|de|in|uk|us|io|gov|cn|ml|ai|ly|dev|cloud|me|icu|ru|info|top|tk|tr|cn|ga|cf|nl)).*(?<!png|jpeg|jpg|txt|blob|css|html|js|svg))"
      ),
      false,
      "",
      Map(),
      NodeType.API,
      "",
      CatLevelOne.SINKS,
      catLevelTwo = Constants.third_parties,
      Language.JAVASCRIPT,
      Array()
    )
  )

  val systemConfig = List(
    SystemConfig(
      "apiHttpLibraries",
      "(?i)(request|fetch|axios|vue-axios|urllib|http|client|react-query|socket(.){0,1}io|xmlhttprequest|node.http|cors|got|apollo|superagent|wretch|@angular\\/common\\/http|@(.){2,25}\\/http|.*(HttpClient)).*",
      Language.JAVASCRIPT,
      "",
      Array()
    ),
    SystemConfig(
      "apiSinks",
      "(?i)(?:url|client|openConnection|request|execute|newCall|load|host|access|usequery|fetch|fetchapi|fetchlegacyxml|createfetch|postform|axios|cors|get|getInputStream|getApod|getForObject|getForEntity|list|set|put|post|proceed|trace|patch|Path|send|sendAsync|remove|delete|write|read|assignment|provider|exchange|postForEntity|call|createCall|createEndpoint|dispatch|invoke|newMessage|getInput|getOutput|getResponse|marshall|unmarshall|send|asyncSend|emit|on)",
      Language.JAVASCRIPT,
      "",
      Array()
    ),
    SystemConfig(
      "apiIdentifier",
      "(?i).*((hook|base|auth|prov|endp|install|cloud|host|request|service|gateway|route|resource|upload|api|worker|tracker)(.){0,12}url|(slack|web)(.){0,4}hook|(sentry|segment)(.){0,1}(dsn)|(rest|api|host|cloud|request|service)(.){0,4}(endpoint|gateway|route)).*",
      Language.JAVASCRIPT,
      "",
      Array()
    ),
    SystemConfig(
      "clientCreationBaseUrlPattern",
      "(?i)(axios.*create|(@angular\\/common\\/http.){0,1}HttpRequest<any>[:]clone)",
      Language.JAVASCRIPT,
      "",
      Array()
    ),
    SystemConfig(
      "ignoredSinks",
      "(?i).*(?<=map|list|jsonobject|json|array|arrays|jsonnode|objectmapper|objectnode).*(put:|get:).*",
      Language.JAVASCRIPT,
      "",
      Array()
    )
  )

  val privadoInput = PrivadoInput()

  val axiosCreateCPG = apiSampleCode("""
      |import axios from "axios";
      |
      |export const baseUrl = "http://google.com:3300";
      |
      |const axiosInstance = axios.create({
      |  baseURL: baseUrl
      |});
      |
      |let obj = {};
      |const getRankNames =  async () => {
      |  for(let i = 0; i < 24; i++ ) {
      |    await axiosInstance.get(`/top/list?idx=${i}`).then(data => {
      |      obj[i] = data.playlist.name;
      |    })
      |  }
      |  console.log(obj);
      |}
      |
      |getRankNames();
      |""".stripMargin)

  "Axios Create sample" should {

    "axios.create should be tagged" in {
      val callNode = axiosCreateCPG.call.methodFullName("axios.*create").head
      callNode.name shouldBe "create"
      callNode.tag.size shouldBe 6
      callNode.tag.nameExact(Constants.id).head.value shouldBe (Constants.thirdPartiesAPIRuleId + ".google.com")
      callNode.tag.nameExact(Constants.catLevelOne).head.value shouldBe Constants.sinks
      callNode.tag.nameExact(Constants.catLevelTwo).head.value shouldBe Constants.third_parties
      callNode.tag.nameExact(Constants.nodeType).head.value shouldBe "api"
      callNode.tag.nameExact("third_partiesapi").head.value shouldBe (Constants.thirdPartiesAPIRuleId + ".google.com")
      callNode.tag.nameExact("apiUrlSinks.ThirdParties.API.google.com").head.value shouldBe ("google.com")
    }
  }

  val angularInterceptorCPG = apiSampleCode("""
      |import { Injectable } from "@angular/core";
      |import {
      |  HttpEvent,
      |  HttpInterceptor,
      |  HttpHandler,
      |  HttpRequest,
      |} from "@angular/common/http";
      |import { Observable } from "rxjs";
      |
      |@Injectable({ providedIn: "root" })
      |export class ApiInterceptor implements HttpInterceptor {
      |  intercept(
      |    req: HttpRequest<any>,
      |    next: HttpHandler
      |  ): Observable<HttpEvent<any>> {
      |    const apiReq = req.clone({ url: `https://api.realworld.io/api${req.url}` });
      |    return next.handle(apiReq);
      |  }
      |}
      |""".stripMargin)

  "Angular Interceptor Sample" should {

    "Angular interceptor req.clone should be tagged" in {
      val callNode = angularInterceptorCPG.call.methodFullName("HttpRequest.*clone").head
      callNode.name shouldBe "clone"
      callNode.tag.size shouldBe 6
      callNode.tag.nameExact(Constants.id).head.value shouldBe (Constants.thirdPartiesAPIRuleId + ".api.realworld.io")
      callNode.tag.nameExact(Constants.catLevelOne).head.value shouldBe Constants.sinks
      callNode.tag.nameExact(Constants.catLevelTwo).head.value shouldBe Constants.third_parties
      callNode.tag.nameExact(Constants.nodeType).head.value shouldBe "api"
      callNode.tag
        .nameExact("third_partiesapi")
        .head
        .value shouldBe (Constants.thirdPartiesAPIRuleId + ".api.realworld.io")
      callNode.tag.nameExact("apiUrlSinks.ThirdParties.API.api.realworld.io").head.value shouldBe ("api.realworld.io")
    }
  }

  def apiSampleCode(code: String): Cpg = {
    val inputDir = File.newTemporaryDirectory()
    inputDirs.addOne(inputDir)
    (inputDir / "axios-create.js").write(code)
    val outputFile = File.newTemporaryFile()
    outPutFiles.addOne(outputFile)
    val rule: ConfigAndRules =
      ConfigAndRules(sourceRule, sinkRule, List(), List(), List(), List(), List(), List(), systemConfig, List())
    val ruleCache = new RuleCache()
    ruleCache.setRule(rule)
    val config      = Config().withInputPath(inputDir.toString()).withOutputPath(outputFile.toString())
    val cpg         = new JsSrc2Cpg().createCpgWithAllOverlays(config).get
    val taggerCache = new TaggerCache()
    new IdentifierTagger(cpg, ruleCache, taggerCache).createAndApply()
    new JSAPITagger(cpg, ruleCache, privadoInput).createAndApply()
    cpgs.addOne(cpg)
    cpg
  }
}
