import lotus.domino.*;

/**
 * Example Java agent (newagentjava).
 */
public class JavaAgent extends AgentBase {

	public void NotesMain() {
		try {
			System.out.println("Hello World");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
