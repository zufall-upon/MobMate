from fastapi import FastAPI
from fastapi.responses import FileResponse
from pydantic import BaseModel
from TTS.api import TTS

app = FastAPI()

tts = TTS(
    model_name="tts_models/multilingual/multi-dataset/xtts_v2",
    progress_bar=False,
    gpu=False
)

class TTSReq(BaseModel):
    text: str
    language: str = "en"

@app.post("/tts")
def tts_api(req: TTSReq):
    speaker_wav = "vv_4146689104366679984.wav"  # ★ 3〜10s .wav

    tts.tts_to_file(
        text=req.text,
        language=req.language,
        speaker_wav=speaker_wav,
        file_path="out.wav",
    )

    return FileResponse("out.wav", media_type="audio/wav")

@app.get("/health")
def health():
    return {"status": "ok"}