package sttp.tapir.server.interpreter

import sttp.model.headers.Cookie
import sttp.model.{HeaderNames, Method, QueryParams}
import sttp.tapir.internal._
import sttp.tapir.model.ServerRequest
import sttp.tapir.{DecodeResult, EndpointIO, EndpointInput, StreamBodyIO}

import scala.annotation.tailrec

sealed trait DecodeBasicInputsResult
object DecodeBasicInputsResult {

  /** @param basicInputsValues Values of basic inputs, in order as they are defined in the endpoint. */
  case class Values(
      basicInputsValues: Vector[Any],
      bodyInputWithIndex: Option[(Either[EndpointIO.Body[_, _], EndpointIO.StreamBodyWrapper[_, _]], Int)]
  ) extends DecodeBasicInputsResult {
    private def verifyNoBody(input: EndpointInput[_]): Unit = if (bodyInputWithIndex.isDefined) {
      throw new IllegalStateException(s"Double body definition: $input")
    }
    def addBodyInput(input: EndpointIO.Body[_, _], bodyIndex: Int): Values = {
      verifyNoBody(input)
      copy(bodyInputWithIndex = Some((Left(input), bodyIndex)))
    }
    def addStreamingBodyInput(input: EndpointIO.StreamBodyWrapper[_, _], bodyIndex: Int): Values = {
      verifyNoBody(input)
      copy(bodyInputWithIndex = Some((Right(input), bodyIndex)))
    }

    /** Sets the value of the body input, once it is known, if a body input is defined. */
    def setBodyInputValue(v: Any): Values = bodyInputWithIndex match {
      case Some((_, i)) => copy(basicInputsValues = basicInputsValues.updated(i, v))
      case None         => this
    }

    def setBasicInputValue(v: Any, i: Int): Values = copy(basicInputsValues = basicInputsValues.updated(i, v))
  }
  case class Failure(input: EndpointInput.Basic[_], failure: DecodeResult.Failure) extends DecodeBasicInputsResult
}

private case class DecodeInputsContext(request: ServerRequest, pathSegments: List[String]) {
  def method: Method = request.method
  def nextPathSegment: (Option[String], DecodeInputsContext) =
    pathSegments match {
      case Nil    => (None, this)
      case h :: t => (Some(h), DecodeInputsContext(request, t))
    }
  def header(name: String): List[String] = request.headers(name).toList
  def headers: Seq[(String, String)] = request.headers.map(h => (h.name, h.value))
  def queryParameter(name: String): Seq[String] = queryParameters.getMulti(name).getOrElse(Nil)
  val queryParameters: QueryParams = request.queryParameters
}

object DecodeBasicInputs {
  case class IndexedBasicInput(input: EndpointInput.Basic[_], index: Int)

  /** Decodes values of all basic inputs defined by the given `input`, and returns a map from the input to the input's value.
    *
    * An exception is the body input, which is not decoded. This is because typically bodies can be only read once. That's why, all non-body
    * inputs are used to decide if a request matches the endpoint, or not. If a body input is present, it is also returned as part of the
    * result.
    *
    * In case any of the decoding fails, the failure is returned together with the failing input.
    */
  def apply(input: EndpointInput[_], request: ServerRequest): DecodeBasicInputsResult =
    apply(input, DecodeInputsContext(request, request.pathSegments))

  private def apply(input: EndpointInput[_], ctx: DecodeInputsContext): DecodeBasicInputsResult = {
    // The first decoding failure is returned.
    // We decode in the following order: path, method, query, headers (incl. cookies), request, status, body
    // An exact-path check is done after path & method matching

    val basicInputs = input.asVectorOfBasicInputs().zipWithIndex.map { case (el, i) => IndexedBasicInput(el, i) }

    val methodInputs = basicInputs.filter(t => isRequestMethod(t.input))
    val pathInputs = basicInputs.filter(t => isPath(t.input))
    val otherInputs = basicInputs.filterNot(t => isRequestMethod(t.input) || isPath(t.input)).sortBy(t => basicInputSortIndex(t.input))

    // we're using null as a placeholder for the future values. All except the body (which is determined by
    // interpreter-specific code), should be filled by the end of this method.
    compose(
      matchPath(pathInputs, _, _),
      matchOthers(methodInputs, _, _),
      matchOthers(otherInputs, _, _)
    )(DecodeBasicInputsResult.Values(Vector.fill(basicInputs.size)(null), None), ctx)._1
  }

  /** We're decoding paths differently than other inputs. We first map all path segments to their decoding results (not checking if this is
    * a successful or failed decoding at this stage). This is collected as the `decodedPathInputs` value.
    *
    * Once this is done, we check if there are remaining path segments. If yes - the decoding fails with a `Mismatch`.
    *
    * Hence, a failure due to a mismatch in the number of segments takes **priority** over any potential failures in decoding the segments.
    */
  private def matchPath(
      pathInputs: Vector[IndexedBasicInput],
      decodeValues: DecodeBasicInputsResult.Values,
      ctx: DecodeInputsContext
  ): (DecodeBasicInputsResult, DecodeInputsContext) = {
    pathInputs.initAndLast match {
      case None =>
        // Match everything if no path input is specified
        (decodeValues, ctx)
      case Some((_, last)) =>
        matchPathInner(
          pathInputs = pathInputs,
          ctx = ctx,
          decodeValues = decodeValues,
          decodedPathInputs = Vector.empty,
          lastPathInput = last
        )
    }
  }

  @tailrec
  private def matchPathInner(
      pathInputs: Vector[IndexedBasicInput],
      ctx: DecodeInputsContext,
      decodeValues: DecodeBasicInputsResult.Values,
      decodedPathInputs: Vector[(IndexedBasicInput, DecodeResult[_])],
      lastPathInput: IndexedBasicInput
  ): (DecodeBasicInputsResult, DecodeInputsContext) = {
    pathInputs.headAndTail match {
      case Some((idxInput @ IndexedBasicInput(in, _), restInputs)) =>
        in match {
          case EndpointInput.FixedPath(expectedSegment, codec, _) =>
            val (nextSegment, newCtx) = ctx.nextPathSegment
            nextSegment match {
              case Some(seg) =>
                if (seg == expectedSegment) {
                  val newDecodedPathInputs = decodedPathInputs :+ ((idxInput, codec.decode(seg)))
                  matchPathInner(restInputs, newCtx, decodeValues, newDecodedPathInputs, idxInput)
                } else {
                  val failure = DecodeBasicInputsResult.Failure(in, DecodeResult.Mismatch(expectedSegment, seg))
                  (failure, newCtx)
                }
              case None =>
                if (expectedSegment.isEmpty) {
                  // FixedPath("") matches an empty path
                  val newDecodedPathInputs = decodedPathInputs :+ ((idxInput, codec.decode("")))
                  matchPathInner(restInputs, newCtx, decodeValues, newDecodedPathInputs, idxInput)
                } else {
                  // shape path mismatch - input path too short
                  val failure = DecodeBasicInputsResult.Failure(in, DecodeResult.Missing)
                  (failure, newCtx)
                }
            }
          case i: EndpointInput.PathCapture[_] =>
            val (nextSegment, newCtx) = ctx.nextPathSegment
            nextSegment match {
              case Some(seg) =>
                val newDecodedPathInputs = decodedPathInputs :+ ((idxInput, i.codec.decode(seg)))
                matchPathInner(restInputs, newCtx, decodeValues, newDecodedPathInputs, idxInput)
              case None =>
                val failure = DecodeBasicInputsResult.Failure(in, DecodeResult.Missing)
                (failure, newCtx)
            }
          case i: EndpointInput.PathsCapture[_] =>
            val (paths, newCtx) = collectRemainingPath(Vector.empty, ctx)
            val newDecodedPathInputs = decodedPathInputs :+ ((idxInput, i.codec.decode(paths.toList)))
            matchPathInner(restInputs, newCtx, decodeValues, newDecodedPathInputs, idxInput)
          case _ =>
            throw new IllegalStateException(s"Unexpected EndpointInput ${in.show} encountered. This is most likely a bug in the library")
        }
      case None =>
        val (extraSegmentOpt, newCtx) = ctx.nextPathSegment
        extraSegmentOpt match {
          case Some(_) =>
            // shape path mismatch - input path too long; there are more segments in the request path than expected by
            // that input. Reporting a failure on the last path input.
            val failure =
              DecodeBasicInputsResult.Failure(lastPathInput.input, DecodeResult.Multiple(collectRemainingPath(Vector.empty, ctx)._1))
            (failure, newCtx)
          case None =>
            (foldDecodedPathInputs(decodedPathInputs, decodeValues), newCtx)
        }
    }
  }

  @tailrec
  private def foldDecodedPathInputs(
      decodedPathInputs: Vector[(IndexedBasicInput, DecodeResult[_])],
      acc: DecodeBasicInputsResult.Values
  ): DecodeBasicInputsResult = {
    decodedPathInputs.headAndTail match {
      case None => acc
      case Some((t, ts)) =>
        t match {
          case (indexedInput, failure: DecodeResult.Failure) => DecodeBasicInputsResult.Failure(indexedInput.input, failure)
          case (indexedInput, DecodeResult.Value(v))         => foldDecodedPathInputs(ts, acc.setBasicInputValue(v, indexedInput.index))
        }
    }
  }

  @tailrec
  private def collectRemainingPath(acc: Vector[String], c: DecodeInputsContext): (Vector[String], DecodeInputsContext) =
    c.nextPathSegment match {
      case (Some(s), c2) => collectRemainingPath(acc :+ s, c2)
      case (None, c2)    => (acc, c2)
    }

  @tailrec
  private def matchOthers(
      inputs: Vector[IndexedBasicInput],
      values: DecodeBasicInputsResult.Values,
      ctx: DecodeInputsContext
  ): (DecodeBasicInputsResult, DecodeInputsContext) = {
    inputs.headAndTail match {
      case None => (values, ctx)
      case Some((IndexedBasicInput(input @ EndpointIO.Body(_, _, _), index), inputsTail)) =>
        matchOthers(inputsTail, values.addBodyInput(input, index), ctx)
      case Some((IndexedBasicInput(input @ EndpointIO.StreamBodyWrapper(StreamBodyIO(_, _, _, _)), index), inputsTail)) =>
        matchOthers(inputsTail, values.addStreamingBodyInput(input, index), ctx)
      case Some((indexedInput, inputsTail)) =>
        val (result, ctx2) = matchOther(indexedInput.input, ctx)
        result match {
          case DecodeResult.Value(v)         => matchOthers(inputsTail, values.setBasicInputValue(v, indexedInput.index), ctx2)
          case failure: DecodeResult.Failure => (DecodeBasicInputsResult.Failure(indexedInput.input, failure), ctx2)
        }
    }
  }

  private def matchOther(input: EndpointInput.Basic[_], ctx: DecodeInputsContext): (DecodeResult[_], DecodeInputsContext) = {
    input match {
      case EndpointInput.FixedMethod(m, codec, _) =>
        if (m == ctx.method) (codec.decode(()), ctx)
        else (DecodeResult.Mismatch(m.method, ctx.method.method), ctx)

      case EndpointIO.FixedHeader(sttp.model.Header(n, v), codec, _) =>
        if (ctx.header(n) == Nil) (DecodeResult.Missing, ctx)
        else if (List(v) == ctx.header(n)) (codec.decode(()), ctx)
        else (DecodeResult.Mismatch(List(v).mkString, ctx.header(n).mkString), ctx)

      case EndpointInput.Query(name, codec, _) =>
        (codec.decode(ctx.queryParameter(name).toList), ctx)

      case EndpointInput.QueryParams(codec, _) =>
        (codec.decode(ctx.queryParameters), ctx)

      case EndpointInput.Cookie(name, codec, _) =>
        val allCookies = DecodeResult
          .sequence(
            ctx.headers
              .filter(_._1.equalsIgnoreCase(HeaderNames.Cookie))
              .map(p =>
                Cookie.parse(p._2) match {
                  case Left(e)  => DecodeResult.Error(p._2, new RuntimeException(e))
                  case Right(c) => DecodeResult.Value(c)
                }
              )
          )
          .map(_.flatten)
        val decodedCookieValue = allCookies.map(_.find(_.name == name).map(_.value)).flatMap(codec.decode)
        (decodedCookieValue, ctx)

      case EndpointIO.Header(name, codec, _) =>
        (codec.decode(ctx.header(name)), ctx)

      case EndpointIO.Headers(codec, _) =>
        (codec.decode(ctx.headers.map((sttp.model.Header.apply _).tupled).toList), ctx)

      case EndpointInput.ExtractFromRequest(codec, _) =>
        (codec.decode(ctx.request), ctx)

      case EndpointIO.Empty(codec, _) =>
        (codec.decode(()), ctx)

      case input =>
        throw new IllegalStateException(
          s"Unexpected EndpointInput ${input.show} encountered. This is most likely a bug in the library"
        )
    }
  }

  private val isRequestMethod: EndpointInput.Basic[_] => Boolean = {
    case _: EndpointInput.FixedMethod[_] => true
    case _                               => false
  }

  private val isPath: EndpointInput.Basic[_] => Boolean = {
    case _: EndpointInput.FixedPath[_]    => true
    case _: EndpointInput.PathCapture[_]  => true
    case _: EndpointInput.PathsCapture[_] => true
    case _                                => false
  }

  private type DecodeInputResultTransform =
    (DecodeBasicInputsResult.Values, DecodeInputsContext) => (DecodeBasicInputsResult, DecodeInputsContext)
  private def compose(fs: DecodeInputResultTransform*): DecodeInputResultTransform = { (values, ctx) =>
    fs match {
      case f +: tail =>
        f(values, ctx) match {
          case (values2: DecodeBasicInputsResult.Values, ctx2) => compose(tail: _*)(values2, ctx2)
          case r                                               => r
        }
      case _ => (values, ctx)
    }
  }
}
