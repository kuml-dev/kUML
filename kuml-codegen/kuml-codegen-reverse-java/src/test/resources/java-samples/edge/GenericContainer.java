package edge;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Edge case test: generic class with template parameters,
 * Map fields (REV-J-003), Optional fields.
 */
public class GenericContainer<T extends Comparable<T>> {
    private T value;
    private Optional<T> optionalValue;
    private List<T> values;
    private Map<String, T> mappedValues; // should trigger REV-J-003
    private UnknownExternalType externalRef; // should trigger REV-J-002

    public GenericContainer(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    public Optional<T> getOptionalValue() {
        return optionalValue;
    }

    public List<T> getValues() {
        return values;
    }
}
