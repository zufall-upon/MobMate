package io.github.ggerganov.whispercpp.params;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Structure;

public class WhisperAhead extends Structure {
    public int n_text_layer;
    public int n_head;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("n_text_layer", "n_head");
    }

    public WhisperAhead(int n_text_layer, int n_head) {
        super();
        this.n_text_layer = n_text_layer;
        this.n_head = n_head;

    }

    public static class ByReference extends WhisperAhead implements Structure.ByReference {

        public ByReference(int n_text_layer, int n_head) {
            super(n_text_layer, n_head);
        }

    };

    public static class ByValue extends WhisperAhead implements Structure.ByValue {

        public ByValue(int n_text_layer, int n_head) {
            super(n_text_layer, n_head);
        }

    };
}
