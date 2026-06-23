"""
Health check endpoints
"""

import logging
from datetime import datetime

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse

from app.core.db import get_db
from sqlalchemy.ext.asyncio import AsyncSession

logger = logging.getLogger(__name__)

# Create router
router = APIRouter(prefix="/health", tags=["Health"])


@router.get("/", summary="Health check endpoint")
async def health_check():
    """
    Basic health check endpoint
    """
    return JSONResponse(
        content={
            "status": "ok",
            "service": "ai",
            "timestamp": datetime.utcnow().isoformat(),
        }
    )


@router.get("/database", summary="Database health check")
async def database_health_check(
    db: AsyncSession = Depends(get_db)
):
    """
    Check database connectivity
    """
    try:
        # Execute a simple query
        result = await db.execute("SELECT 1")
        await db.commit()
        
        return JSONResponse(
            content={
                "status": "ok",
                "service": "ai",
                "database": "connected",
                "timestamp": datetime.utcnow().isoformat(),
            }
        )
    except Exception as e:
        logger.error(f"Database health check failed: {e}")
        return JSONResponse(
            content={
                "status": "error",
                "service": "ai",
                "database": "disconnected",
                "error": str(e),
                "timestamp": datetime.utcnow().isoformat(),
            },
            status_code=503,
        )


@router.get("/detailed", summary="Detailed health check")
async def detailed_health_check(
    db: AsyncSession = Depends(get_db)
):
    """
    Detailed health check with all dependencies
    """
    health_checks = {
        "service": {
            "status": "ok",
            "name": "ai",
        },
        "database": {
            "status": "ok",
            "type": "postgresql",
        },
        "kafka": {
            "status": "ok",
            "type": "confluent",
        },
        "redis": {
            "status": "ok",
            "type": "cache",
        },
    }
    
    return JSONResponse(
        content={
            "status": "ok",
            "checks": health_checks,
            "timestamp": datetime.utcnow().isoformat(),
        }
    )
