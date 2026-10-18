# Online inference service. Only the code is in the image; the model artifacts and the
# feature configuration are mounted read-only at run time. No dataset files are available.
FROM python:3.13.9-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY src/ src/

ENV PYTHONPATH=/app/src \
    PAA_FEATURE_CONFIG=/config/feature-groups.json \
    PAA_ARTIFACTS_DIR=/artifacts

RUN useradd --system --uid 10001 app
USER app

EXPOSE 8000
HEALTHCHECK --interval=5s --timeout=3s --retries=12 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)"

CMD ["python", "-m", "ml_service.inference", "--host", "0.0.0.0", "--port", "8000"]
