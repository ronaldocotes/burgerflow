"""
Database configuration and connection management
"""

import asyncio
import logging
from contextlib import asynccontextmanager
from typing import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from app.core.config import settings

logger = logging.getLogger(__name__)


# Database URL
DATABASE_URL = settings.DATABASE_URL

# Database engine
db_engine = create_async_engine(
    DATABASE_URL,
    pool_size=settings.DATABASE_POOL_SIZE,
    max_overflow=settings.DATABASE_MAX_OVERFLOW,
    pool_timeout=settings.DATABASE_POOL_TIMEOUT,
    pool_recycle=settings.DATABASE_POOL_RECYCLE,
    echo=settings.DATABASE_ECHO,
)

# Session factory
async_session_maker = async_sessionmaker(
    db_engine,
    class_=AsyncSession,
    expire_on_commit=False,
    autocommit=False,
    autoflush=False,
)


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """Dependency to get database session"""
    async with async_session_maker() as session:
        try:
            yield session
        except Exception as e:
            logger.error(f"Database error: {e}", exc_info=True)
            await session.rollback()
            raise
        finally:
            await session.close()


@asynccontextmanager
async def get_db_session() -> AsyncGenerator[AsyncSession, None]:
    """Context manager for database session"""
    session = async_session_maker()
    try:
        yield session
    except Exception as e:
        logger.error(f"Database error: {e}", exc_info=True)
        await session.rollback()
        raise
    finally:
        await session.close()


async def init_db():
    """Initialize database connection"""
    logger.info("Initializing database connection...")
    try:
        # Test connection
        async with db_engine.begin() as conn:
            await conn.run_sync(lambda connection: connection.execute("SELECT 1"))
        logger.info("Database connection established successfully")
    except Exception as e:
        logger.error(f"Failed to initialize database: {e}", exc_info=True)
        raise


async def close_db():
    """Close database connection"""
    logger.info("Closing database connection...")
    try:
        await db_engine.dispose()
        logger.info("Database connection closed successfully")
    except Exception as e:
        logger.error(f"Failed to close database: {e}", exc_info=True)
        raise


# Base model for SQLAlchemy
class Base(DeclarativeBase):
    """Base class for all SQLAlchemy models"""
    pass
