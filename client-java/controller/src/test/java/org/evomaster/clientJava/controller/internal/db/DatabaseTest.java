package org.evomaster.clientJava.controller.internal.db;

import io.restassured.http.ContentType;
import org.evomaster.clientJava.controller.InstrumentedSutStarter;
import org.evomaster.clientJava.controller.db.DataRow;
import org.evomaster.clientJava.controller.db.QueryResult;
import org.evomaster.clientJava.controller.db.SqlScriptRunner;
import org.evomaster.clientJava.controllerApi.dto.SutRunDto;
import org.evomaster.clientJava.instrumentation.InstrumentingAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static io.restassured.RestAssured.given;
import static org.evomaster.clientJava.controllerApi.ControllerConstants.*;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

public class DatabaseTest {

    /*
        Useful link:
        https://www.tutorialspoint.com/sql/index.htm
     */

    private static Connection connection;


    @BeforeAll
    public static void initClass() throws Exception {
        InstrumentingAgent.initP6Spy("org.h2.Driver");

        connection = DriverManager.getConnection("jdbc:p6spy:h2:mem:db_test", "sa", "");
    }

    @BeforeEach
    public void initTest() throws Exception {

        /*
            Not supported in H2
            SqlScriptRunner.execCommand(connection, "DROP DATABASE db_test;");
            SqlScriptRunner.execCommand(connection, "CREATE DATABASE db_test;");
        */

        //custom H2 command
        SqlScriptRunner.execCommand(connection, "DROP ALL OBJECTS;");
    }


    @Test
    public void testBase() throws Exception {

        SqlScriptRunner.execCommand(connection, "CREATE TABLE Foo(x INT)");

        QueryResult res = SqlScriptRunner.execCommand(connection, "select * from Foo");
        assertTrue(res.isEmpty());

        SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (x) VALUES (4)");

        res = SqlScriptRunner.execCommand(connection, "select * from Foo");
        assertFalse(res.isEmpty());
    }


    @Test
    public void testParentheses() throws Exception{

        SqlScriptRunner.execCommand(connection, "CREATE TABLE Foo(x INT)");
        SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (x) VALUES (5)");

        QueryResult res = SqlScriptRunner.execCommand(connection, "select * from Foo where x = (5)");
        assertFalse(res.isEmpty());
    }


    @Test
    public void testConstants() throws Exception {

        SqlScriptRunner.execCommand(connection, "CREATE TABLE Foo(x INT)");
        SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (x) VALUES (4)");

        String select = "select x, 1 as y, null as z, 'bar' as w from Foo";

        QueryResult res = SqlScriptRunner.execCommand(connection, select);
        assertFalse(res.isEmpty());

        DataRow row = res.seeRows().get(0);
        assertEquals(4, row.getValue(0));
        assertEquals(1, row.getValue(1));
        assertEquals(null, row.getValue(2));
        assertEquals("bar", row.getValue(3));
    }

    @Test
    public void testNested() throws Exception{

        String select = "select t.a, t.b from (select x as a, 1 as b from Foo where x<10) t where a>3";

        SqlScriptRunner.execCommand(connection, "CREATE TABLE Foo(x INT)");
        SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (x) VALUES (1)");
        SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (x) VALUES (4)");
        SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (x) VALUES (7)");
        SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (x) VALUES (20)");

        QueryResult res = SqlScriptRunner.execCommand(connection, select);
        assertEquals(2, res.size());
    }


    @Test
    public void testHeuristic() throws Exception {

        SqlScriptRunner.execCommand(connection, "CREATE TABLE Foo(x INT)");
        SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (x) VALUES (10)");

        InstrumentedSutStarter starter = getInstrumentedSutStarter();

        try {
            String url = start(starter);

            given().accept(ContentType.JSON)
                    .get(url)
                    .then()
                    .statusCode(200)
                    .body("toMinimize.size()", is(0));

            SqlScriptRunner.execCommand(connection, "SELECT x FROM Foo WHERE x = 12");

            given().accept(ContentType.JSON)
                    .get(url)
                    .then()
                    .statusCode(200)
                    .body("toMinimize.size()", is(1))
                    .body("toMinimize[0]", greaterThan(0f));

            SqlScriptRunner.execCommand(connection, "SELECT x FROM Foo WHERE x = 10");

            given().accept(ContentType.JSON)
                    .get(url)
                    .then()
                    .statusCode(200)
                    .body("toMinimize.size()", is(2))
                    .body("toMinimize[0]", greaterThan(0f))
                    .body("toMinimize[1]", is(0f));

            given().delete(url)
                    .then()
                    .statusCode(204);

            given().accept(ContentType.JSON)
                    .get(url)
                    .then()
                    .statusCode(200)
                    .body("toMinimize.size()", is(0));

        } finally {
            starter.stop();
        }
    }


    @Test
    public void testVarNotInSelect() throws Exception {

        SqlScriptRunner.execCommand(connection, "CREATE TABLE Foo(x INT, y INT)");
        SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (x, y) VALUES (0, 0)");

        int y = 42;
        String select = "select f.x from Foo f where f.y=" + y;


        InstrumentedSutStarter starter = getInstrumentedSutStarter();

        try {
            String url = start(starter);

            SqlScriptRunner.execCommand(connection, select);

            double a = getFirstAndDelete(url);
            assertTrue(a > 0d);

            SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (x, y) VALUES (1, " + y + ")");
            SqlScriptRunner.execCommand(connection, select);

            double b = getFirstAndDelete(url);
            assertTrue(b < a);
            assertEquals(0d, b, 0.0001);

        } finally {
            starter.stop();
        }
    }

    @Test
    public void testInnerJoin() throws Exception {

        SqlScriptRunner.execCommand(connection, "CREATE TABLE Bar(id INT Primary Key, value INT)");
        SqlScriptRunner.execCommand(connection, "CREATE TABLE Foo(id INT Primary Key, value INT, bar_id INT, " +
                "CONSTRAINT fk FOREIGN KEY (bar_id) REFERENCES Bar(id) )");

        SqlScriptRunner.execCommand(connection, "INSERT INTO Bar (id, value) VALUES (0, 0)");
        SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (id, value, bar_id) VALUES (0, 0, 0)");

        int x = 10;
        int y = 20;

        String select = "select f.id, f.value, f.bar_id  from Foo f inner join Bar b on f.bar_id=b.id " +
                "where f.value=" + x + " and b.value=" + y + " limit 1";

        InstrumentedSutStarter starter = getInstrumentedSutStarter();

        try {
            String url = start(starter);

            SqlScriptRunner.execCommand(connection, select);

            double a = getFirstAndDelete(url);
            assertTrue(a > 0d);

            SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (id, value, bar_id) VALUES (1, " + x + ", 0)");
            SqlScriptRunner.execCommand(connection, select);

            double b = getFirstAndDelete(url);
            assertTrue(b < a);

            SqlScriptRunner.execCommand(connection, "INSERT INTO Bar (id, value) VALUES (1, " + y + ")");
            SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (id, value, bar_id) VALUES (2, 0, 1)");
            SqlScriptRunner.execCommand(connection, select);

            double c = getFirstAndDelete(url);
            assertTrue(c < b);

            SqlScriptRunner.execCommand(connection, "INSERT INTO Bar (id, value) VALUES (2, " + y + ")");
            SqlScriptRunner.execCommand(connection, "INSERT INTO Foo (id, value, bar_id) VALUES (3, " + x + ", 2)");
            SqlScriptRunner.execCommand(connection, select);

            double d = getFirstAndDelete(url);
            assertTrue(d < c);
            assertEquals(0d, d, 0.0001);

        } finally {
            starter.stop();
        }
    }

    private String start(InstrumentedSutStarter starter) {
        boolean started = starter.start();
        assertTrue(started);

        int port = starter.getControllerServerPort();

        startSut(port);

        return "http://localhost:" + port + BASE_PATH + EXTRA_HEURISTICS;
    }


    private void startSut(int port) {
        given().contentType(ContentType.JSON)
                .body(new SutRunDto(true, false))
                .put("http://localhost:" + port + BASE_PATH + RUN_SUT_PATH)
                .then()
                .statusCode(204);
    }

    private Double getFirstAndDelete(String url) {
        double value = Double.parseDouble(given().accept(ContentType.JSON)
                .get(url)
                .then()
                .statusCode(200)
                .extract().body().path("toMinimize[0]").toString());
        given().delete(url).then().statusCode(204);

        return value;
    }

    private InstrumentedSutStarter getInstrumentedSutStarter() {
        DatabaseFakeSutController sutController = new DatabaseFakeSutController(connection);
        sutController.setControllerPort(0);
        return new InstrumentedSutStarter(sutController);
    }

}
