"""Run the inference service:  python -m ml_service.inference [--host HOST] [--port PORT]"""

import argparse

import uvicorn

from ml_service.inference.app import create_app


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the ML inference service")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    uvicorn.run(create_app(), host=args.host, port=args.port)


if __name__ == "__main__":
    main()
