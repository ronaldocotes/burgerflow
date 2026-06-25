"""
MenuFlow AI API V1 package.

Only import routers that exist in this codebase. Future modules such as
chatbot, recommendations, WhatsApp and analytics should be added here when
their files and tests land.
"""

from . import demand_forecasting, health

__all__ = [
    "health",
    "demand_forecasting",
]
