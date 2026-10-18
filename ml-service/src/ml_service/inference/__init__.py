"""Online inference service.

This package may only depend on the configuration, encoding and artifact modules. It must
not import ``ml_service.data``, ``ml_service.training`` or ``ml_service.analysis``: online
inference never reads dataset files or labels, only the saved model.
"""
