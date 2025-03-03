package sttp.tapir.server.tests

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import io.circe.generic.auto._
import org.scalatest
import org.scalatest.matchers.should.Matchers._
import sttp.client3._
import sttp.model._
import sttp.model.headers.{CookieValueWithMeta, CookieWithMeta}
import sttp.monad.MonadError
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.model.UsernamePassword
import sttp.tapir.server.interceptor.decodefailure.{DecodeFailureHandler, DefaultDecodeFailureHandler}
import sttp.tapir.tests.MultipleMediaTypes.{
  organizationHtmlIso,
  organizationHtmlUtf8,
  organizationJson,
  organizationXml,
  out_json_xml_text_common_schema
}
import sttp.tapir.tests.TestUtil._
import sttp.tapir.tests._

import java.io.{ByteArrayInputStream, File, InputStream}
import java.nio.ByteBuffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class ServerBasicTests[F[_], ROUTE](
    createServerTest: CreateServerTest[F, Any, ROUTE],
    serverInterpreter: TestServerInterpreter[F, Any, ROUTE],
    multipleValueHeaderSupport: Boolean = true,
    inputStreamSupport: Boolean = true,
    supportsUrlEncodedPathSegments: Boolean = true,
    supportsMultipleSetCookieHeaders: Boolean = true
)(implicit
    m: MonadError[F]
) {
  import createServerTest._
  import serverInterpreter._

  private val basicStringRequest = basicRequest.response(asStringAlways)
  private def pureResult[T](t: T): F[T] = m.unit(t)
  private def suspendResult[T](t: => T): F[T] = m.eval(t)

  def tests(): List[Test] =
    basicTests() ++ (if (inputStreamSupport) inputStreamTests() else Nil)

  def basicTests(): List[Test] = List(
    testServer(in_string_out_status_from_type_erasure_using_partial_matcher)((v: String) =>
      pureResult((if (v == "right") Some(Right("right")) else if (v == "left") Some(Left(42)) else None).asRight[Unit])
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?fruit=nothing").send(backend).map(_.code shouldBe StatusCode.NoContent) >>
        basicRequest.get(uri"$baseUri?fruit=right").send(backend).map(_.code shouldBe StatusCode.Ok) >>
        basicRequest.get(uri"$baseUri?fruit=left").send(backend).map(_.code shouldBe StatusCode.Accepted)
    },
    // method matching
    testServer(endpoint, "GET empty endpoint")((_: Unit) => pureResult(().asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(baseUri).send(backend).map(_.body shouldBe Right(""))
    },
    testServer(endpoint, "POST empty endpoint")((_: Unit) => pureResult(().asRight[Unit])) { (backend, baseUri) =>
      basicRequest.post(baseUri).send(backend).map(_.body shouldBe Right(""))
    },
    testServer(out_fixed_content_type_header, "Fixed content-type header")((_: Unit) => pureResult("".asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest.get(baseUri).send(backend).map(_.headers.toSet should contain(Header("Content-Type", "text/csv")))
    },
    testServer(endpoint.get, "GET a GET endpoint")((_: Unit) => pureResult(().asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(baseUri).send(backend).map(_.body shouldBe Right(""))
    },
    testServer(endpoint.get, "POST a GET endpoint")((_: Unit) => pureResult(().asRight[Unit])) { (backend, baseUri) =>
      basicRequest.post(baseUri).send(backend).map(_.body shouldBe Symbol("left"))
    },
    //
    testServer(in_query_out_string)((fruit: String) => pureResult(s"fruit: $fruit".asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?fruit=orange").send(backend).map(_.body shouldBe Right("fruit: orange"))
    },
    testServer(in_query_out_string, "with URL encoding")((fruit: String) => pureResult(s"fruit: $fruit".asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri?fruit=red%20apple").send(backend).map(_.body shouldBe Right("fruit: red apple"))
    },
    testServer[String, Nothing, String](in_query_out_infallible_string)((fruit: String) => pureResult(s"fruit: $fruit".asRight[Nothing])) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri?fruit=kiwi").send(backend).map(_.body shouldBe Right("fruit: kiwi"))
    },
    testServer(in_query_query_out_string) { case (fruit: String, amount: Option[Int]) => pureResult(s"$fruit $amount".asRight[Unit]) } {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri?fruit=orange").send(backend).map(_.body shouldBe Right("orange None")) *>
          basicRequest.get(uri"$baseUri?fruit=orange&amount=10").send(backend).map(_.body shouldBe Right("orange Some(10)"))
    },
    testServer(in_header_out_string)((p1: String) => pureResult(s"$p1".asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri").header("X-Role", "Admin").send(backend).map(_.body shouldBe Right("Admin"))
    },
    testServer(in_path_path_out_string) { case (fruit: String, amount: Int) => pureResult(s"$fruit $amount".asRight[Unit]) } {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri/fruit/orange/amount/20").send(backend).map(_.body shouldBe Right("orange 20"))
    },
    testServer(in_path_path_out_string, "with URL encoding") { case (fruit: String, amount: Int) =>
      pureResult(s"$fruit $amount".asRight[Unit])
    } { (backend, baseUri) =>
      if (supportsUrlEncodedPathSegments) {
        basicRequest.get(uri"$baseUri/fruit/apple%2Fred/amount/20").send(backend).map(_.body shouldBe Right("apple/red 20"))
      } else {
        IO.pure(succeed)
      }
    },
    testServer(in_path, "Empty path should not be passed to path capture decoding") { _ => pureResult(Right(())) } { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/api/").send(backend).map(_.code shouldBe StatusCode.NotFound)
    },
    testServer(in_two_path_capture, "capturing two path parameters with the same specification") { case (a: Int, b: Int) =>
      pureResult(Right((a, b)))
    } { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/in/12/23").send(backend).map { response =>
        response.header("a") shouldBe Some("12")
        response.header("b") shouldBe Some("23")
      }
    },
    testServer(in_string_out_string)((b: String) => pureResult(b.asRight[Unit])) { (backend, baseUri) =>
      basicRequest.post(uri"$baseUri/api/echo").body("Sweet").send(backend).map(_.body shouldBe Right("Sweet"))
    },
    testServer(in_string_out_string, "with get method")((b: String) => pureResult(b.asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/api/echo").body("Sweet").send(backend).map(_.body shouldBe Symbol("left"))
    },
    testServer(in_mapped_query_out_string)((fruit: List[Char]) => pureResult(s"fruit length: ${fruit.length}".asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri?fruit=orange").send(backend).map(_.body shouldBe Right("fruit length: 6"))
    },
    testServer(in_mapped_path_out_string)((fruit: Fruit) => pureResult(s"$fruit".asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/fruit/kiwi").send(backend).map(_.body shouldBe Right("Fruit(kiwi)"))
    },
    testServer(in_mapped_path_path_out_string)((p1: FruitAmount) => pureResult(s"FA: $p1".asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/fruit/orange/amount/10").send(backend).map(_.body shouldBe Right("FA: FruitAmount(orange,10)"))
    },
    testServer(in_query_mapped_path_path_out_string) { case (fa: FruitAmount, color: String) =>
      pureResult(s"FA: $fa color: $color".asRight[Unit])
    } { (backend, baseUri) =>
      basicRequest
        .get(uri"$baseUri/fruit/orange/amount/10?color=yellow")
        .send(backend)
        .map(_.body shouldBe Right("FA: FruitAmount(orange,10) color: yellow"))
    },
    testServer(in_query_out_mapped_string)((p1: String) => pureResult(p1.toList.asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?fruit=orange").send(backend).map(_.body shouldBe Right("orange"))
    },
    testServer(in_query_out_mapped_string_header)((p1: String) => pureResult(FruitAmount(p1, p1.length).asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri?fruit=orange").send(backend).map { r =>
          r.body shouldBe Right("orange")
          r.header("X-Role") shouldBe Some("6")
        }
    },
    testServer(in_header_before_path, "Header input before path capture input") { case (str: String, i: Int) =>
      pureResult((i, str).asRight[Unit])
    } { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/12").header("SomeHeader", "hello").send(backend).map { response =>
        response.body shouldBe Right("hello")
        response.header("IntHeader") shouldBe Some("12")
      }
    },
    testServer(in_json_out_json)((fa: FruitAmount) => pureResult(FruitAmount(fa.fruit + " banana", fa.amount * 2).asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest
          .post(uri"$baseUri/api/echo")
          .body("""{"fruit":"orange","amount":11}""")
          .send(backend)
          .map(_.body shouldBe Right("""{"fruit":"orange banana","amount":22}"""))
    },
    testServer(in_json_out_json, "with accept header")((fa: FruitAmount) => pureResult(fa.asRight[Unit])) { (backend, baseUri) =>
      basicRequest
        .post(uri"$baseUri/api/echo")
        .body("""{"fruit":"banana","amount":12}""")
        .header(HeaderNames.Accept, sttp.model.MediaType.ApplicationJson.toString)
        .send(backend)
        .map(_.body shouldBe Right("""{"fruit":"banana","amount":12}"""))
    },
    testServer(in_json_out_json, "content type")((fa: FruitAmount) => pureResult(fa.asRight[Unit])) { (backend, baseUri) =>
      basicRequest
        .post(uri"$baseUri/api/echo")
        .body("""{"fruit":"banana","amount":12}""")
        .send(backend)
        .map(_.contentType shouldBe Some(sttp.model.MediaType.ApplicationJson.toString))
    },
    testServer(in_byte_array_out_byte_array)((b: Array[Byte]) => pureResult(b.asRight[Unit])) { (backend, baseUri) =>
      basicRequest.post(uri"$baseUri/api/echo").body("banana kiwi".getBytes).send(backend).map(_.body shouldBe Right("banana kiwi"))
    },
    testServer(in_byte_buffer_out_byte_buffer)((b: ByteBuffer) => pureResult(b.asRight[Unit])) { (backend, baseUri) =>
      basicRequest.post(uri"$baseUri/api/echo").body("mango").send(backend).map(_.body shouldBe Right("mango"))
    },
    testServer(in_unit_out_json_unit, "unit json mapper")((_: Unit) => pureResult(().asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/api/unit").send(backend).map(_.body shouldBe Right("{}"))
    },
    testServer(in_unit_out_string, "default status mapper")((_: Unit) => pureResult("".asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/not-existing-path").send(backend).map(_.code shouldBe StatusCode.NotFound)
    },
    testServer(in_unit_error_out_string, "default error status mapper")((_: Unit) => pureResult("".asLeft[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/api").send(backend).map(_.code shouldBe StatusCode.BadRequest)
    },
    testServer(in_form_out_form)((fa: FruitAmount) => pureResult(fa.copy(fruit = fa.fruit.reverse, amount = fa.amount + 1).asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest
          .post(uri"$baseUri/api/echo")
          .body(Map("fruit" -> "plum", "amount" -> "10"))
          .send(backend)
          .map(_.body shouldBe Right("fruit=mulp&amount=11"))
    },
    testServer(in_query_params_out_string)((mqp: QueryParams) =>
      pureResult(mqp.toSeq.sortBy(_._1).map(p => s"${p._1}=${p._2}").mkString("&").asRight[Unit])
    ) { (backend, baseUri) =>
      val params = Map("name" -> "apple", "weight" -> "42", "kind" -> "very good")
      basicRequest
        .get(uri"$baseUri/api/echo/params?$params")
        .send(backend)
        .map(_.body shouldBe Right("kind=very good&name=apple&weight=42"))
    },
    testServer(in_query_params_out_string, "should support value-less query param")((mqp: QueryParams) =>
      pureResult(mqp.toMultiMap.map(data => s"${data._1}=${data._2.toList}").mkString("&").asRight[Unit])
    ) { (backend, baseUri) =>
      basicRequest
        .get(uri"$baseUri/api/echo/params?flag")
        .send(backend)
        .map(_.body shouldBe Right("flag=List()"))
    },
    testServer(in_headers_out_headers)((hs: List[Header]) => pureResult(hs.map(h => Header(h.name, h.value.reverse)).asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest
          .get(uri"$baseUri/api/echo/headers")
          .headers(Header.unsafeApply("X-Fruit", "apple"), Header.unsafeApply("Y-Fruit", "Orange"))
          .send(backend)
          .map(_.headers should contain allOf (Header.unsafeApply("X-Fruit", "elppa"), Header.unsafeApply("Y-Fruit", "egnarO")))
    },
    testServer(in_paths_out_string)((ps: Seq[String]) => pureResult(ps.mkString(" ").asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/hello/it/is/me/hal").send(backend).map(_.body shouldBe Right("hello it is me hal"))
    },
    testServer(in_paths_out_string, "paths should match empty path")((ps: Seq[String]) => pureResult(ps.mkString(" ").asRight[Unit])) {
      (backend, baseUri) => basicRequest.get(uri"$baseUri").send(backend).map(_.body shouldBe Right(""))
    },
    testServer(in_query_out_string, "invalid query parameter")((fruit: String) => pureResult(s"fruit: $fruit".asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri?fruit2=orange").send(backend).map(_.code shouldBe StatusCode.BadRequest)
    },
    testServer(in_query_list_out_header_list)((l: List[String]) => pureResult(("v0" :: l).reverse.asRight[Unit])) { (backend, baseUri) =>
      basicRequest
        .get(uri"$baseUri/api/echo/param-to-header?qq=${List("v1", "v2", "v3")}")
        .send(backend)
        .map { r =>
          if (multipleValueHeaderSupport) {
            r.headers.filter(_.is("hh")).map(_.value).toSet shouldBe Set("v3", "v2", "v1", "v0")
          } else {
            r.headers.filter(_.is("hh")).map(_.value).headOption should contain("v3, v2, v1, v0")
          }
        }
    },
    testServer(in_cookies_out_cookies)((cs: List[sttp.model.headers.Cookie]) =>
      pureResult(cs.map(c => CookieWithMeta.unsafeApply(c.name, c.value.reverse)).asRight[Unit])
    ) { (backend, baseUri) =>
      if (supportsMultipleSetCookieHeaders) {
        basicRequest.get(uri"$baseUri/api/echo/headers").cookies(("c1", "v1"), ("c2", "v2")).send(backend).map { r =>
          r.unsafeCookies.map(c => (c.name, c.value)).toSet shouldBe Set(("c1", "1v"), ("c2", "2v"))
        }
      } else {
        IO.pure(succeed)
      }
    }, // Fails because of lack in Zio Http support for Set-Cookie header https://github.com/dream11/zio-http/issues/187
    testServer(in_set_cookie_value_out_set_cookie_value)((c: CookieValueWithMeta) =>
      pureResult(c.copy(value = c.value.reverse).asRight[Unit])
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/api/echo/headers").header("Set-Cookie", "c1=xy; HttpOnly; Path=/").send(backend).map { r =>
        r.unsafeCookies.toList shouldBe List(
          CookieWithMeta.unsafeApply("c1", "yx", None, None, None, Some("/"), secure = false, httpOnly = true)
        )
      }
    },
    testServer(in_string_out_content_type_string, "dynamic content type")((b: String) => pureResult((b, "image/png").asRight[Unit])) {
      (backend, baseUri) =>
        basicStringRequest.get(uri"$baseUri/api/echo").body("test").send(backend).map { r =>
          r.contentType shouldBe Some("image/png")
          r.body shouldBe "test"
        }
    },
    testServer(in_content_type_out_string)((ct: String) => pureResult(ct.asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/api/echo").contentType("application/dicom+json").send(backend).map { r =>
        r.body shouldBe Right("application/dicom+json")
      }
    },
    testServer(in_content_type_fixed_header, "mismatch content-type")((_: Unit) => pureResult(().asRight[Unit])) { (backend, baseUri) =>
      basicRequest
        .post(uri"$baseUri/api/echo")
        .contentType(MediaType.ApplicationXml)
        .send(backend)
        .map(_.code shouldBe StatusCode.UnsupportedMediaType)
    },
    testServer(in_content_type_fixed_header, "missing content-type")((_: Unit) => pureResult(().asRight[Unit])) { (backend, baseUri) =>
      basicRequest
        .post(uri"$baseUri/api/echo")
        .send(backend)
        .map(_.code shouldBe StatusCode.BadRequest)
    },
    testServer(in_content_type_header_with_custom_decode_results, "mismatch content-type")((_: MediaType) =>
      pureResult(Either.right[Unit, Unit](()))
    ) { (backend, baseUri) =>
      basicRequest
        .post(uri"$baseUri/api/echo")
        .contentType(MediaType.ApplicationXml)
        .send(backend)
        .map(_.code shouldBe StatusCode.UnsupportedMediaType)
    },
    testServer(in_content_type_header_with_custom_decode_results, "missing content-type")((_: MediaType) =>
      pureResult(Either.right[Unit, Unit](()))
    ) { (backend, baseUri) =>
      basicRequest
        .post(uri"$baseUri/api/echo")
        .send(backend)
        .map(_.code shouldBe StatusCode.BadRequest)
    },
    testServer(in_unit_out_html)(_ => pureResult("<html />".asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/api/echo").send(backend).map { r =>
        r.contentType shouldBe Some("text/html; charset=UTF-8")
      }
    },
    testServer(in_unit_out_header_redirect)(_ => pureResult("http://new.com".asRight[Unit])) { (backend, baseUri) =>
      basicRequest.followRedirects(false).get(uri"$baseUri").send(backend).map { r =>
        r.code shouldBe StatusCode.PermanentRedirect
        r.header("Location") shouldBe Some("http://new.com")
      }
    },
    testServer(in_unit_out_fixed_header)(_ => pureResult(().asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri").send(backend).map { r => r.header("Location") shouldBe Some("Poland") }
    },
    testServer(in_optional_json_out_optional_json)((fa: Option[FruitAmount]) => pureResult(fa.asRight[Unit])) { (backend, baseUri) =>
      basicRequest
        .post(uri"$baseUri/api/echo")
        .send(backend)
        .map { r =>
          r.code shouldBe StatusCode.Ok
          r.body shouldBe Right("")
        } >>
        basicRequest
          .post(uri"$baseUri/api/echo")
          .body("""{"fruit":"orange","amount":11}""")
          .send(backend)
          .map(_.body shouldBe Right("""{"fruit":"orange","amount":11}"""))
    },
    // path matching
    testServer(endpoint, "no path should match anything")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri").send(backend).map(_.code shouldBe StatusCode.Ok) >>
        basicRequest.get(uri"$baseUri/").send(backend).map(_.code shouldBe StatusCode.Ok) >>
        basicRequest.get(uri"$baseUri/nonemptypath").send(backend).map(_.code shouldBe StatusCode.Ok) >>
        basicRequest.get(uri"$baseUri/nonemptypath/nonemptypath2").send(backend).map(_.code shouldBe StatusCode.Ok)
    },
    testServer(in_root_path, "root path should not match non-root path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri/nonemptypath").send(backend).map(_.code shouldBe StatusCode.NotFound)
    },
    testServer(in_root_path, "root path should match empty path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri").send(backend).map(_.code shouldBe StatusCode.Ok)
    },
    testServer(in_root_path, "root path should match root path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri/").send(backend).map(_.code shouldBe StatusCode.Ok)
    },
    testServer(in_single_path, "single path should match single path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri/api").send(backend).map(_.code shouldBe StatusCode.Ok)
    },
    testServer(in_single_path, "single path should match single/ path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri/api/").send(backend).map(_.code shouldBe StatusCode.Ok)
    },
    testServer(in_path_paths_out_header_body, "Capturing paths after path capture") { case (i, paths) =>
      pureResult(Right((i, paths.mkString(","))))
    } { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/api/15/and/some/more/path").send(backend).map { r =>
        r.code shouldBe StatusCode.Ok
        r.header("IntPath") shouldBe Some("15")
        r.body shouldBe Right("some,more,path")
      }
    },
    testServer(in_path_paths_out_header_body, "Capturing paths after path capture (when empty)") { case (i, paths) =>
      pureResult(Right((i, paths.mkString(","))))
    } { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/api/15/and/").send(backend).map { r =>
        r.code shouldBe StatusCode.Ok
        r.header("IntPath") shouldBe Some("15")
        r.body shouldBe Right("")
      }
    },
    testServer(in_single_path, "single path should not match root path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri").send(backend).map(_.code shouldBe StatusCode.NotFound) >>
          basicRequest.get(uri"$baseUri/").send(backend).map(_.code shouldBe StatusCode.NotFound)
    },
    testServer(in_single_path, "single path should not match larger path")((_: Unit) => pureResult(Either.right[Unit, Unit](()))) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri/api/echo/hello").send(backend).map(_.code shouldBe StatusCode.NotFound) >>
          basicRequest.get(uri"$baseUri/api/echo/").send(backend).map(_.code shouldBe StatusCode.NotFound)
    },
    testServer(in_string_out_status, "custom status code")((_: String) => pureResult(StatusCode(431).asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?fruit=apple").send(backend).map(_.code shouldBe StatusCode(431))
    },
    testServer(in_string_out_status_from_string)((v: String) => pureResult((if (v == "apple") Right("x") else Left(10)).asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri?fruit=apple").send(backend).map(_.code shouldBe StatusCode.Ok) >>
          basicRequest.get(uri"$baseUri?fruit=orange").send(backend).map(_.code shouldBe StatusCode.Accepted)
    },
    testServer(in_int_out_value_form_exact_match)((num: Int) => pureResult(if (num % 2 == 0) Right("A") else Right("B"))) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri/mapping?num=1").send(backend).map(_.code shouldBe StatusCode.Ok) >>
          basicRequest.get(uri"$baseUri/mapping?num=2").send(backend).map(_.code shouldBe StatusCode.Accepted)
    },
    testServer(in_string_out_status_from_string_one_empty)((v: String) =>
      pureResult((if (v == "apple") Right("x") else Left(())).asRight[Unit])
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?fruit=apple").send(backend).map(_.code shouldBe StatusCode.Ok) >>
        basicRequest.get(uri"$baseUri?fruit=orange").send(backend).map(_.code shouldBe StatusCode.Accepted)
    },
    testServer(in_extract_request_out_string)((v: String) => pureResult(v.asRight[Unit])) { (backend, baseUri) =>
      basicStringRequest.get(uri"$baseUri").send(backend).map(_.body shouldBe "GET") >>
        basicStringRequest.post(uri"$baseUri").send(backend).map(_.body shouldBe "POST")
    },
    testServer(in_string_out_status)((v: String) =>
      pureResult((if (v == "apple") StatusCode.Accepted else StatusCode.NotFound).asRight[Unit])
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?fruit=apple").send(backend).map(_.code shouldBe StatusCode.Accepted) >>
        basicRequest.get(uri"$baseUri?fruit=orange").send(backend).map(_.code shouldBe StatusCode.NotFound)
    },
    // path shape matching
    testServer(
      in_path_fixed_capture_fixed_capture,
      "Returns 400 if path 'shape' matches, but failed to parse a path parameter",
      Some(decodeFailureHandlerBadRequestOnPathFailure)
    )(_ => pureResult(Either.right[Unit, Unit](()))) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/customer/asd/orders/2").send(backend).map { response =>
        response.body shouldBe Left("Invalid value for: path parameter customer_id")
        response.code shouldBe StatusCode.BadRequest
      }
    },
    testServer(
      in_path_fixed_capture_fixed_capture,
      "Returns 404 if path 'shape' doesn't match",
      Some(decodeFailureHandlerBadRequestOnPathFailure)
    )(_ => pureResult(Either.right[Unit, Unit](()))) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/customer").send(backend).map(response => response.code shouldBe StatusCode.NotFound) >>
        basicRequest.get(uri"$baseUri/customer/asd").send(backend).map(response => response.code shouldBe StatusCode.NotFound) >>
        basicRequest
          .get(uri"$baseUri/customer/asd/orders/2/xyz")
          .send(backend)
          .map(response => response.code shouldBe StatusCode.NotFound)
    },
    // auth
    testServer(in_auth_apikey_header_out_string)((s: String) => pureResult(s.asRight[Unit])) { (backend, baseUri) =>
      basicStringRequest.get(uri"$baseUri/auth").header("X-Api-Key", "1234").send(backend).map(_.body shouldBe "1234")
    },
    testServer(in_auth_apikey_query_out_string)((s: String) => pureResult(s.asRight[Unit])) { (backend, baseUri) =>
      basicStringRequest.get(uri"$baseUri/auth?api-key=1234").send(backend).map(_.body shouldBe "1234")
    },
    testServer(in_auth_basic_out_string)((up: UsernamePassword) => pureResult(up.toString.asRight[Unit])) { (backend, baseUri) =>
      basicStringRequest
        .get(uri"$baseUri/auth")
        .auth
        .basic("teddy", "bear")
        .send(backend)
        .map(_.body shouldBe "UsernamePassword(teddy,Some(bear))")
    },
    testServer(in_auth_bearer_out_string)((s: String) => pureResult(s.asRight[Unit])) { (backend, baseUri) =>
      basicStringRequest.get(uri"$baseUri/auth").auth.bearer("1234").send(backend).map(_.body shouldBe "1234")
    },
    //
    testServer(
      "two endpoints with increasingly specific path inputs: should match path exactly",
      NonEmptyList.of(
        route(endpoint.get.in("p1").out(stringBody).serverLogic((_: Unit) => pureResult("e1".asRight[Unit]))),
        route(endpoint.get.in("p1" / "p2").out(stringBody).serverLogic((_: Unit) => pureResult("e2".asRight[Unit])))
      )
    ) { (backend, baseUri) =>
      basicStringRequest.get(uri"$baseUri/p1").send(backend).map(_.body shouldBe "e1") >>
        basicStringRequest.get(uri"$baseUri/p1/p2").send(backend).map(_.body shouldBe "e2")
    },
    testServer(
      "two endpoints with a body defined as the first input: should only consume body when the path matches",
      NonEmptyList.of(
        route(
          endpoint.post
            .in(byteArrayBody)
            .in("p1")
            .out(stringBody)
            .serverLogic((s: Array[Byte]) => pureResult(s"p1 ${s.length}".asRight[Unit]))
        ),
        route(
          endpoint.post
            .in(byteArrayBody)
            .in("p2")
            .out(stringBody)
            .serverLogic((s: Array[Byte]) => pureResult(s"p2 ${s.length}".asRight[Unit]))
        )
      )
    ) { (backend, baseUri) =>
      basicStringRequest
        .post(uri"$baseUri/p2")
        .body("a" * 1000000)
        .send(backend)
        .map { r => r.body shouldBe "p2 1000000" }
    },
    testServer(
      "two endpoints with query defined as the first input, path segments as second input: should try the second endpoint if the path doesn't match",
      NonEmptyList.of(
        route(endpoint.get.in(query[String]("q1")).in("p1").serverLogic((_: String) => pureResult(().asRight[Unit]))),
        route(endpoint.get.in(query[String]("q2")).in("p2").serverLogic((_: String) => pureResult(().asRight[Unit])))
      )
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/p1?q1=10").send(backend).map(_.code shouldBe StatusCode.Ok) >>
        basicRequest.get(uri"$baseUri/p1?q2=10").send(backend).map(_.code shouldBe StatusCode.BadRequest) >>
        basicRequest.get(uri"$baseUri/p2?q2=10").send(backend).map(_.code shouldBe StatusCode.Ok) >>
        basicRequest.get(uri"$baseUri/p2?q1=10").send(backend).map(_.code shouldBe StatusCode.BadRequest)
    },
    testServer(
      "two endpoints with increasingly specific path inputs, first with a required query parameter: should match path exactly",
      NonEmptyList.of(
        route(endpoint.get.in("p1").in(query[String]("q1")).out(stringBody).serverLogic((_: String) => pureResult("e1".asRight[Unit]))),
        route(endpoint.get.in("p1" / "p2").out(stringBody).serverLogic((_: Unit) => pureResult("e2".asRight[Unit])))
      )
    ) { (backend, baseUri) => basicStringRequest.get(uri"$baseUri/p1/p2").send(backend).map(_.body shouldBe "e2") },
    testServer(
      "two endpoints with validation: should not try the second one if validation fails",
      NonEmptyList.of(
        route(
          endpoint.get.in("p1" / path[String].validate(Validator.minLength(5))).serverLogic((_: String) => pureResult(().asRight[Unit]))
        ),
        route(endpoint.get.in("p2").serverLogic((_: Unit) => pureResult(().asRight[Unit])))
      )
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/p1/abcde").send(backend).map(_.code shouldBe StatusCode.Ok) >>
        basicRequest.get(uri"$baseUri/p1/ab").send(backend).map(_.code shouldBe StatusCode.BadRequest) >>
        basicRequest.get(uri"$baseUri/p2").send(backend).map(_.code shouldBe StatusCode.Ok)
    },
    testServer(in_header_out_header_unit_extended)(in => pureResult(in.asRight[Unit])) { (backend, baseUri) =>
      basicRequest
        .get(uri"$baseUri")
        .header("A", "1")
        .header("X", "3")
        .send(backend)
        .map(_.headers.map(h => h.name.toLowerCase -> h.value).toSet should contain allOf ("y" -> "3", "b" -> "2"))
    },
    testServer(in_4query_out_4header_extended)(in => pureResult(in.asRight[Unit])) { (backend, baseUri) =>
      basicRequest
        .get(uri"$baseUri?a=1&b=2&x=3&y=4")
        .send(backend)
        .map(_.headers.map(h => h.name.toLowerCase -> h.value).toSet should contain allOf ("a" -> "1", "b" -> "2", "x" -> "3", "y" -> "4"))
    },
    testServer(in_3query_out_3header_mapped_to_tuple)(in => pureResult(in.asRight[Unit])) { (backend, baseUri) =>
      basicRequest
        .get(uri"$baseUri?p1=1&p2=2&p3=3")
        .send(backend)
        .map(_.headers.map(h => h.name.toLowerCase -> h.value).toSet should contain allOf ("p1" -> "1", "p2" -> "2", "p3" -> "3"))
    },
    testServer(in_2query_out_2query_mapped_to_unit)(in => pureResult(in.asRight[Unit])) { (backend, baseUri) =>
      basicRequest
        .get(uri"$baseUri?p1=1&p2=2")
        .send(backend)
        .map(_.headers.map(h => h.name.toLowerCase -> h.value).toSet should contain allOf ("p1" -> "DEFAULT_HEADER", "p2" -> "2"))
    },
    testServer(in_query_with_default_out_string)(in => pureResult(in.asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?p1=x").send(backend).map(_.body shouldBe Right("x")) >>
        basicRequest.get(uri"$baseUri").send(backend).map(_.body shouldBe Right("DEFAULT"))
    },
    testServer(out_json_or_default_json)(entityType =>
      pureResult((if (entityType == "person") Person("mary", 20) else Organization("work")).asRight[Unit])
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri/entity/person").send(backend).map { r =>
        r.code shouldBe StatusCode.Created
        r.body.right.get should include("mary")
      } >>
        basicRequest.get(uri"$baseUri/entity/org").send(backend).map { r =>
          r.code shouldBe StatusCode.Ok
          r.body.right.get should include("work")
        }
    },
    //
    testServer(endpoint, "handle exceptions")(_ => throw new RuntimeException()) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri").send(backend).map(_.code shouldBe StatusCode.InternalServerError)
    },
    testServer(out_json_xml_text_common_schema)(_ => pureResult(Organization("sml").asRight[Unit])) { (backend, baseUri) =>
      def ok(body: String) = (StatusCode.Ok, body.asRight[String])
      def unsupportedMediaType() = (StatusCode.UnsupportedMediaType, "".asLeft[String])
      def badRequest() = (StatusCode.BadRequest, "".asLeft[String])

      val cases: Map[(String, String), (StatusCode, Either[String, String])] = Map(
        ("application/json", "*") -> ok(organizationJson),
        ("application/xml", "*") -> ok(organizationXml),
        ("text/html", "*") -> ok(organizationHtmlUtf8),
        ("text/html;q=0.123, application/json;q=0.124, application/xml;q=0.125", "*") -> ok(organizationXml),
        ("application/xml, application/json", "*") -> ok(organizationXml),
        ("application/json, application/xml", "*") -> ok(organizationJson),
        ("application/xml;q=0.5, application/json;q=0.9", "*") -> ok(organizationJson),
        ("application/json;q=0.5, application/xml;q=0.5", "*") -> ok(organizationJson),
        ("application/json, application/xml, text/*;q=0.1", "iso-8859-1") -> ok(organizationHtmlIso),
        ("text/*;q=0.5, application/*", "*") -> ok(organizationJson),
        ("text/*;q=0.5, application/xml;q=0.3", "utf-8") -> ok(organizationHtmlUtf8),
        ("text/html", "utf-8;q=0.9, iso-8859-1;q=0.5") -> ok(organizationHtmlUtf8),
        ("text/html", "utf-8;q=0.5, iso-8859-1;q=0.9") -> ok(organizationHtmlIso),
        ("text/html", "utf-8, iso-8859-1") -> ok(organizationHtmlUtf8),
        ("text/html", "iso-8859-1, utf-8") -> ok(organizationHtmlIso),
        ("*/*", "iso-8859-1") -> ok(organizationHtmlIso),
        ("*/*", "*;q=0.5, iso-8859-1") -> ok(organizationHtmlIso),
        //
        ("text/html", "iso-8859-5") -> unsupportedMediaType(),
        ("text/csv", "*") -> unsupportedMediaType(),
        // in case of an invalid accepts header, the first mapping should be used
        ("text/html;(q)=xxx", "utf-8") -> ok(organizationJson)
      )

      cases.foldLeft(IO(scalatest.Assertions.succeed))((prev, next) => {
        val ((accept, acceptCharset), (code, body)) = next
        prev >> basicRequest
          .get(uri"$baseUri/content-negotiation/organization")
          .header(HeaderNames.Accept, accept)
          .header(HeaderNames.AcceptCharset, acceptCharset)
          .send(backend)
          .map { response =>
            response.code shouldBe code
            response.body shouldBe body
          }
      })
    },
    testServer(in_root_path, testNameSuffix = "accepts header without output body")(_ => pureResult(().asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest.header(HeaderNames.Accept, "text/plain").get(uri"$baseUri").send(backend).map(_.code shouldBe StatusCode.Ok)
    },
    testServer(
      "recover errors from exceptions",
      NonEmptyList.of(
        routeRecoverErrors(endpoint.in(query[String]("name")).errorOut(jsonBody[FruitError]).out(stringBody), throwFruits)
      )
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?name=apple").send(backend).map(_.body shouldBe Right("ok")) >>
        basicRequest.get(uri"$baseUri?name=banana").send(backend).map { r =>
          r.code shouldBe StatusCode.BadRequest
          r.body shouldBe Left("""{"msg":"no bananas","code":102}""")
        } >>
        basicRequest.get(uri"$baseUri?name=orange").send(backend).map { r =>
          r.code shouldBe StatusCode.InternalServerError
          r.body shouldBe Symbol("left")
        }
    },
    testServer(Validation.in_query_tagged, "support query validation with tagged type")((_: String) => pureResult(().asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri?fruit=apple").send(backend).map(_.code shouldBe StatusCode.Ok) >>
          basicRequest.get(uri"$baseUri?fruit=orange").send(backend).map(_.code shouldBe StatusCode.BadRequest) >>
          basicRequest.get(uri"$baseUri?fruit=banana").send(backend).map(_.code shouldBe StatusCode.Ok)
    },
    testServer(Validation.in_query, "support query validation")((_: Int) => pureResult(().asRight[Unit])) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?amount=3").send(backend).map(_.code shouldBe StatusCode.Ok) >>
        basicRequest.get(uri"$baseUri?amount=-3").send(backend).map(_.code shouldBe StatusCode.BadRequest)
    },
    testServer(Validation.in_valid_json, "support jsonBody validation with wrapped type")((_: ValidFruitAmount) =>
      pureResult(().asRight[Unit])
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri").body("""{"fruit":"orange","amount":11}""").send(backend).map(_.code shouldBe StatusCode.Ok) >>
        basicRequest
          .get(uri"$baseUri")
          .body("""{"fruit":"orange","amount":0}""")
          .send(backend)
          .map(_.code shouldBe StatusCode.BadRequest) >>
        basicRequest.get(uri"$baseUri").body("""{"fruit":"orange","amount":1}""").send(backend).map(_.code shouldBe StatusCode.Ok)
    },
    testServer(Validation.in_valid_query, "support query validation with wrapper type")((_: IntWrapper) => pureResult(().asRight[Unit])) {
      (backend, baseUri) =>
        basicRequest.get(uri"$baseUri?amount=11").send(backend).map(_.code shouldBe StatusCode.Ok) >>
          basicRequest.get(uri"$baseUri?amount=0").send(backend).map(_.code shouldBe StatusCode.BadRequest) >>
          basicRequest.get(uri"$baseUri?amount=1").send(backend).map(_.code shouldBe StatusCode.Ok)
    },
    testServer(Validation.in_valid_json_collection, "support jsonBody validation with list of wrapped type")((_: BasketOfFruits) =>
      pureResult(().asRight[Unit])
    ) { (backend, baseUri) =>
      basicRequest
        .get(uri"$baseUri")
        .body("""{"fruits":[{"fruit":"orange","amount":11}]}""")
        .send(backend)
        .map(_.code shouldBe StatusCode.Ok) >>
        basicRequest.get(uri"$baseUri").body("""{"fruits": []}""").send(backend).map(_.code shouldBe StatusCode.BadRequest) >>
        basicRequest
          .get(uri"$baseUri")
          .body("""{fruits":[{"fruit":"orange","amount":0}]}""")
          .send(backend)
          .map(_.code shouldBe StatusCode.BadRequest)
    },
    //
    testServerLogic(
      endpoint
        .in(query[String]("x"))
        .serverLogicForCurrent(v => pureResult(v.toInt.asRight[Unit]))
        .in(query[String]("y"))
        .out(plainBody[Int])
        .serverLogic { case (x, y) => pureResult((x * y.toInt).asRight[Unit]) },
      "partial server logic - current, one part"
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?x=2&y=3").send(backend).map(_.body shouldBe Right("6"))
    },
    testServerLogic(
      endpoint
        .in(query[String]("x"))
        .serverLogicForCurrent(v => pureResult(v.toInt.asRight[Unit]))
        .in(query[String]("y"))
        .serverLogicForCurrent(v => pureResult(v.toLong.asRight[Unit]))
        .in(query[String]("z"))
        .out(plainBody[Long])
        .serverLogic { case ((x, y), z) => pureResult((x * y * z.toLong).asRight[Unit]) },
      "partial server logic - current, two parts"
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?x=2&y=3&z=5").send(backend).map(_.body shouldBe Right("30"))
    },
    testServerLogic(
      endpoint
        .in(query[String]("x"))
        .in(query[String]("y"))
        .serverLogicForCurrent { case (x, y) => pureResult((x.toInt + y.toInt).asRight[Unit]) }
        .in(query[String]("z"))
        .in(query[String]("u"))
        .out(plainBody[Int])
        .serverLogic { case (xy, (z, u)) => pureResult((xy * z.toInt * u.toInt).asRight[Unit]) },
      "partial server logic - current, one part, multiple values"
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?x=2&y=3&z=5&u=7").send(backend).map(_.body shouldBe Right("175"))
    },
    testServerLogic(
      endpoint
        .in(query[String]("x"))
        .in(query[String]("y"))
        .out(plainBody[Int])
        .serverLogicPart((x: String) => pureResult(x.toInt.asRight[Unit]))
        .andThen { case (x, y) => pureResult((x * y.toInt).asRight[Unit]) },
      "partial server logic - parts, one part"
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?x=2&y=3").send(backend).map(_.body shouldBe Right("6"))
    },
    testServerLogic(
      endpoint
        .in(query[String]("x"))
        .in(query[String]("y"))
        .in(query[String]("z"))
        .out(plainBody[Long])
        .serverLogicPart((x: String) => pureResult(x.toInt.asRight[Unit]))
        .andThenPart((y: String) => pureResult(y.toLong.asRight[Unit]))
        .andThen { case ((x, y), z) => pureResult((x * y * z.toLong).asRight[Unit]) },
      "partial server logic - parts, two parts"
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?x=2&y=3&z=5").send(backend).map(_.body shouldBe Right("30"))
    },
    testServerLogic(
      endpoint
        .in(query[String]("x"))
        .in(query[String]("y"))
        .in(query[String]("z"))
        .in(query[String]("u"))
        .out(plainBody[Int])
        .serverLogicPart { (t: (String, String)) => pureResult((t._1.toInt + t._2.toInt).asRight[Unit]) }
        .andThen { case (xy, (z, u)) => pureResult((xy * z.toInt * u.toInt).asRight[Unit]) },
      "partial server logic - parts, one part, multiple values"
    ) { (backend, baseUri) =>
      basicRequest.get(uri"$baseUri?x=2&y=3&z=5&u=7").send(backend).map(_.body shouldBe Right("175"))
    }
  )

  def inputStreamTests(): List[Test] = List(
    testServer(in_input_stream_out_input_stream)((is: InputStream) =>
      pureResult((new ByteArrayInputStream(inputStreamToByteArray(is)): InputStream).asRight[Unit])
    ) { (backend, baseUri) => basicRequest.post(uri"$baseUri/api/echo").body("mango").send(backend).map(_.body shouldBe Right("mango")) },
    testServer(in_string_out_stream_with_header)(_ => pureResult(Right((new ByteArrayInputStream(Array.fill[Byte](128)(0)), Some(128))))) {
      (backend, baseUri) =>
        basicRequest.post(uri"$baseUri/api/echo").body("test string body").response(asByteArray).send(backend).map { r =>
          r.body.map(_.length) shouldBe Right(128)
          r.body.map(_.foreach(b => b shouldBe 0))
          r.headers.map(_.name.toLowerCase) should contain(HeaderNames.ContentLength.toLowerCase)
          r.header(HeaderNames.ContentLength) shouldBe Some("128")
        }
    }
  )

  val decodeFailureHandlerBadRequestOnPathFailure: DecodeFailureHandler =
    DefaultDecodeFailureHandler.handler.copy(
      respond = DefaultDecodeFailureHandler.respond(
        _,
        badRequestOnPathErrorIfPathShapeMatches = true,
        badRequestOnPathInvalidIfPathShapeMatches = true
      )
    )

  def throwFruits(name: String): F[String] =
    name match {
      case "apple"  => pureResult("ok")
      case "banana" => suspendResult(throw FruitError("no bananas", 102))
      case n        => suspendResult(throw new IllegalArgumentException(n))
    }
}
