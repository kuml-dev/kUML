package edge;

/**
 * Edge case test: inner (nested) class.
 * Inner should appear as a separate UmlClass with enclosing metadata.
 */
public class Outer {
    private String name;
    private Inner inner;

    public Outer(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static class Inner {
        private int value;

        public Inner(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }
}
