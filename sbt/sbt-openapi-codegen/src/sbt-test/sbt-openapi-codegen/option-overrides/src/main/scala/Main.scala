object Main extends App {
  /*
  import sttp.tapir._
  import sttp.tapir.json.circe._
  import io.circe.generic.auto._

  type Limit = Int
  type AuthToken = String
  case class BooksFromYear(genre: String, year: Int)
  case class Book(title: String)

  val booksListing: Endpoint[(BooksFromYear, Limit, AuthToken), String, List[Book], Any] =
    endpoint
      .get
      .in(("books" / path[String]("genre") / path[Int]("year")).mapTo[BooksFromYear])
      .in(query[Limit]("limit").description("Maximum number of books to retrieve"))
      .in(header[AuthToken]("X-Auth-Token"))
      .errorOut(stringBody)
      .out(jsonBody[List[Book]])
   */
  import com.example.generated.apis._
  import sttp.tapir.docs.openapi._
  import sttp.tapir.openapi.circe.yaml._

  val docs = MyExampleEndpoints.generatedEndpoints.toOpenAPI("My Bookshop", "1.0")

  import java.nio.file.{Paths, Files}
  import java.nio.charset.StandardCharsets

  Files.write(Paths.get("target/swagger.yaml"), docs.toYaml.getBytes(StandardCharsets.UTF_8))
}
