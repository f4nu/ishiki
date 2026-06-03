FROM python:3.12-slim

# ca-certificates: required for the HTTPS sync to AnkiWeb
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py anki_client.py ./

ENV COLLECTION_PATH=/data/collection.anki2
EXPOSE 8000

# Single worker only: the anki collection is single-process / single-writer.
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
