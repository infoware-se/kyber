/**
 * Example Java script library (newscriptlibraryjava).
 */
public class DefaultJava {
	public static String hello(String who) {
		if (who == null || who.equals("")) {
			who = "World";
		}
		return "Hello, " + who + "!";
	}

	public static String formatTitle(String title) {
		if (title == null || title.trim().equals("")) {
			return "(untitled)";
		}
		return title.trim();
	}
}
