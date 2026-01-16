from fastapi import FastAPI, Response
from pydantic import BaseModel
from TTS.api import TTS
import io
import soundfile as sf

app = FastAPI()

# CPU固定
tts = TTS("tts_models/multilingual/multi-dataset/xtts_v2").to("cpu")

class TtsReq(BaseModel):
    text: str
    language: str = "en"

@app.get("/health")
def health():
    return {"status": "ok"}

@app.post("/tts")
def tts_api(req: TtsReq):
    wav = tts.tts(
        text=req.text,
        language=req.language,
        speaker_wav="samples/female.wav"
    )

    buf = io.BytesIO()
    sf.write(buf, wav, 24000, format="WAV")

    return Response(
        content=buf.getvalue(),
        media_type="audio/wav"
    )
