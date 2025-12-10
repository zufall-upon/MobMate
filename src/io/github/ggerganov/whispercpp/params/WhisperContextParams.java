package io.github.ggerganov.whispercpp.params;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * Parameters for the whisper_init_from_file_with_params() function. If you change the order or add
 * new parameters, make sure to update the default values in whisper.cpp:
 * whisper_context_default_params()
 */
public class WhisperContextParams extends Structure {
    /** Use GPU for inference Number (default = true) */
    // public CBool use_gpu;
    public byte use_gpu;
    public byte flash_attn;

    public int gpu_device;

    public byte dtw_token_timestamps;
    public int dtw_aheads_preset;
    public int dtw_n_top;

    public WhisperAHeads dtw_aheads;

    public int dtw_mem_size;

    public WhisperContextParams(Pointer p) {
        super(p);
    }

    /** Use GPU for inference Number (default = true) */
    public void useGpu(boolean enable) {
        use_gpu = enable ? (byte) 1 : 0;// CBool.TRUE : CBool.FALSE;
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("use_gpu", "flash_attn", "gpu_device", "dtw_token_timestamps", "dtw_aheads_preset", "dtw_n_top", "dtw_aheads", "dtw_mem_size");
    }

    public static class ByReference extends WhisperContextParams implements Structure.ByReference {

        public ByReference(Pointer p) {
            super(p);
        }

    };

    public static class ByValue extends WhisperContextParams implements Structure.ByValue {

        public ByValue(Pointer p) {
            super(p);
        }

    };

}
