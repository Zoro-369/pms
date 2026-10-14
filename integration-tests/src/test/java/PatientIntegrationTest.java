import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests through the gateway (the full stack must be running: docker compose up -d).
 * The pagination tests create three patients with a unique tag, so they do not depend on how
 * much other data is in the database, and they delete those patients afterwards.
 */
public class PatientIntegrationTest {

  private static final String PATIENTS = "/api/v1/patients";

  private static String token;
  private static String tag;
  private static final List<String> createdIds = new ArrayList<>();

  @BeforeAll
  static void setUp() {
    RestAssured.baseURI = "http://localhost:4004";

    token = given()
            .contentType("application/json")
            .body("{\"email\": \"testuser@test.com\", \"password\": \"password123\"}")
            .when()
            .post("/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("token");

    tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    for (String letter : new String[] {"A", "B", "C"}) {
      String body = String.format(
              "{\"name\": \"PageTest %s %s\", \"email\": \"pagetest-%s-%s@example.com\","
                      + " \"address\": \"1 Test St\", \"dateOfBirth\": \"1990-01-01\","
                      + " \"registeredDate\": \"2024-01-01\"}",
              tag, letter, tag, letter.toLowerCase());
      String id = given()
              .header("Authorization", "Bearer " + token)
              .contentType("application/json")
              .body(body)
              .when()
              .post(PATIENTS)
              .then()
              .statusCode(201)
              .extract()
              .jsonPath()
              .getString("id");
      createdIds.add(id);
    }
  }

  @AfterAll
  static void tearDown() {
    for (String id : createdIds) {
      given().header("Authorization", "Bearer " + token).delete(PATIENTS + "/" + id);
    }
  }

  private static io.restassured.specification.RequestSpecification authed() {
    return given().header("Authorization", "Bearer " + token);
  }

  @Test
  public void shouldRejectRequestWithoutToken() {
    given().when().get(PATIENTS).then().statusCode(401);
  }

  @Test
  public void shouldReturnPagedEnvelopeWithDefaults() {
    authed()
            .when()
            .get(PATIENTS)
            .then()
            .statusCode(200)
            .body("content", notNullValue())
            .body("page", equalTo(0))
            .body("size", equalTo(20))
            .body("totalElements", greaterThanOrEqualTo(15))
            .body("first", equalTo(true));
  }

  @Test
  public void shouldHonourPageSize() {
    authed()
            .queryParam("size", 5)
            .when()
            .get(PATIENTS)
            .then()
            .statusCode(200)
            .body("content", hasSize(5))
            .body("size", equalTo(5))
            .body("totalPages", greaterThanOrEqualTo(3));
  }

  @Test
  public void shouldCapPageSizeAtMaximum() {
    authed()
            .queryParam("size", 1000)
            .when()
            .get(PATIENTS)
            .then()
            .statusCode(200)
            .body("size", equalTo(100));
  }

  @Test
  public void shouldSearchByNameCaseInsensitively() {
    authed()
            .queryParam("search", tag.toUpperCase())
            .when()
            .get(PATIENTS)
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(3));
  }

  @Test
  public void shouldSearchByEmail() {
    authed()
            .queryParam("search", "pagetest-" + tag + "-b@")
            .when()
            .get(PATIENTS)
            .then()
            .statusCode(200)
            .body("totalElements", equalTo(1))
            .body("content[0].name", equalTo("PageTest " + tag + " B"));
  }

  @Test
  public void shouldSortByNameAscending() {
    authed()
            .queryParam("search", tag)
            .queryParam("sort", "name,asc")
            .when()
            .get(PATIENTS)
            .then()
            .statusCode(200)
            .body("content.name", org.hamcrest.Matchers.contains(
                    "PageTest " + tag + " A", "PageTest " + tag + " B", "PageTest " + tag + " C"));
  }

  @Test
  public void shouldSortByNameDescending() {
    authed()
            .queryParam("search", tag)
            .queryParam("sort", "name,desc")
            .when()
            .get(PATIENTS)
            .then()
            .statusCode(200)
            .body("content.name", org.hamcrest.Matchers.contains(
                    "PageTest " + tag + " C", "PageTest " + tag + " B", "PageTest " + tag + " A"));
  }

  @Test
  public void shouldPageThroughSearchResults() {
    authed()
            .queryParam("search", tag)
            .queryParam("sort", "name,asc")
            .queryParam("size", 2)
            .queryParam("page", 0)
            .when()
            .get(PATIENTS)
            .then()
            .statusCode(200)
            .body("content", hasSize(2))
            .body("totalElements", equalTo(3))
            .body("totalPages", equalTo(2))
            .body("first", equalTo(true))
            .body("last", equalTo(false));

    authed()
            .queryParam("search", tag)
            .queryParam("sort", "name,asc")
            .queryParam("size", 2)
            .queryParam("page", 1)
            .when()
            .get(PATIENTS)
            .then()
            .statusCode(200)
            .body("content", hasSize(1))
            .body("first", equalTo(false))
            .body("last", equalTo(true));
  }

  @Test
  public void shouldRejectUnknownSortField() {
    authed()
            .queryParam("sort", "password,asc")
            .when()
            .get(PATIENTS)
            .then()
            .statusCode(400)
            .body("detail", containsString("Unsupported sort field"));
  }

  @Test
  public void shouldReturnEmptyPageWhenNothingMatches() {
    authed()
            .queryParam("search", "no-such-patient-" + UUID.randomUUID())
            .when()
            .get(PATIENTS)
            .then()
            .statusCode(200)
            .body("content", hasSize(0))
            .body("totalElements", equalTo(0));
  }
}