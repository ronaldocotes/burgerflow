"""
BurgerFlow AI Service
=====================

FastAPI application for AI/ML features:
- Demand forecasting
- Chatbot for customer service
- WhatsApp integration
- Recommendation engine
- Image recognition (for product photos)
"""

import logging
from contextlib import asynccontextmanager
from typing import AsyncGenerator

from fastapi import FastAPI, Depends, HTTPException, status, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

# Import routers
from app.api.v1 import (
    chatbot,
    demand_forecasting,
    health,
    recommendations,
    whatsapp,
    analytics,
)

# Import config
from app.core.config import settings
from app.core.db import init_db, close_db
from app.core.kafka import init_kafka, close_kafka
from app.core.logger import setup_logging

# Setup logging
setup_logging()
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Application lifespan manager"""
    # Startup
    logger.info("Starting BurgerFlow AI Service...")
    
    # Initialize database
    logger.info("Initializing database connection...")
    await init_db()
    
    # Initialize Kafka
    logger.info("Initializing Kafka connection...")
    await init_kafka()
    
    logger.info("BurgerFlow AI Service started successfully!")
    
    yield
    
    # Shutdown
    logger.info("Shutting down BurgerFlow AI Service...")
    
    # Close Kafka
    logger.info("Closing Kafka connection...")
    await close_kafka()
    
    # Close database
    logger.info("Closing database connection...")
    await close_db()
    
    logger.info("BurgerFlow AI Service shutdown complete")


# Create FastAPI application
app = FastAPI(
    title=settings.APP_NAME,
    description=settings.APP_DESCRIPTION,
    version=settings.APP_VERSION,
    docs_url="/api/docs",
    redoc_url="/api/redoc",
    openapi_url="/api/openapi.json",
    lifespan=lifespan,
)


# CORS Middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# Trusted Host Middleware (Security)
app.add_middleware(
    TrustedHostMiddleware,
    allowed_hosts=settings.TRUSTED_HOSTS,
)


# Include API routers
app.include_router(health.router, prefix="/api/v1/health", tags=["Health"])
app.include_router(
    demand_forecasting.router,
    prefix="/api/v1/forecast",
    tags=["Demand Forecasting"],
)
app.include_router(
    recommendations.router,
    prefix="/api/v1/recommendations",
    tags=["Recommendations"],
)
app.include_router(
    chatbot.router,
    prefix="/api/v1/chatbot",
    tags=["Chatbot"],
)
app.include_router(
    whatsapp.router,
    prefix="/api/v1/whatsapp",
    tags=["WhatsApp"],
)
app.include_router(
    analytics.router,
    prefix="/api/v1/analytics",
    tags=["Analytics"],
)


# Global exception handler
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """Global exception handler for unhandled exceptions"""
    logger.error(f"Unhandled exception: {exc}", exc_info=True)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={
            "status": "error",
            "message": "An unexpected error occurred",
            "details": str(exc),
        },
    )


# Root endpoint
@app.get("/", tags=["Root"])
async def root():
    """Root endpoint"""
    return {
        "name": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "description": settings.APP_DESCRIPTION,
        "docs": "/api/docs",
    }


# Mount static files (for serving ML models, etc.)
app.mount("/static", StaticFiles(directory="static"), name="static")


# Health check endpoint (separate from API health)
@app.get("/health")
async def health_check():
    """Simple health check endpoint"""
    return {"status": "ok", "service": "ai"}


if __name__ == "__main__":
    import uvicorn
    
    uvicorn.run(
        "main:app",
        host=settings.HOST,
        port=settings.PORT,
        reload=settings.DEBUG,
        log_config=None,
    )
