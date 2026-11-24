# AJ Panel JVM tests

`HaTest` runs the Home Assistant client (`Ws`, `Ha`) against a mock Home Assistant WebSocket server and checks the
dashboard generation (`Dash`), looks (`Skin`) and Arabic strings (`Lang`) – no Android device needed.
`stub/` holds a tiny org.json and a console `Log` used only by the test (the app uses Android's).

    S=apps/panel/src/com/ajnetwork/ajpanel
    javac -encoding UTF-8 -d /tmp/pt $(find apps/panel/test/stub -name '*.java') $S/Ws.java $S/Ha.java $S/HaLogin.java $S/Dash.java $S/Skin.java $S/Lang.java apps/panel/test/HaTest.java
    java -cp /tmp/pt com.ajnetwork.ajpanel.HaTest

CI runs it in `android-java.yml`.
