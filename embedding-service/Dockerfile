FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# app.py is the threaded HTTP entrypoint; app_onnx.py holds the ONNX runtime + handler.
COPY app_onnx.py app.py ./

# Persistent cache for downloaded (or manually placed) models.
VOLUME ["/app/models"]

EXPOSE 80
CMD ["python", "app.py"]
