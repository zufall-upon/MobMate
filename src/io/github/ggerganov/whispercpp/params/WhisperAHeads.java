package io.github.ggerganov.whispercpp.params;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Structure;

public class WhisperAHeads extends Structure {

    public int n_heads;

    public WhisperAhead.ByReference heads;

    public WhisperAHeads() {

    }

    /// @param heads C type : const whisper_ahead*
    public WhisperAHeads(int n_heads, WhisperAhead.ByReference heads) {
        this.n_heads = n_heads;
        this.heads = heads;

    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("n_heads", "heads");
    }

    public static class ByReference extends WhisperAHeads implements Structure.ByReference {

        public ByReference(int n_heads, WhisperAhead.ByReference heads) {
            super(n_heads, heads);
        }

    };

    public static class ByValue extends WhisperAHeads implements Structure.ByValue {

        public ByValue(int n_heads, WhisperAhead.ByReference heads) {
            super(n_heads, heads);
        }

    };
}
