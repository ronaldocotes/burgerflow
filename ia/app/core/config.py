"""
Application configuration using Pydantic Settings
"""

from functools import lru_cache
from typing import List

from pydantic import AnyHttpUrl, field_validator
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Application settings"""
    
    # Application
    APP_NAME: str = "MenuFlow AI"
    APP_DESCRIPTION: str = "AI Service for MenuFlow - Demand forecasting, chatbot, recommendations"
    APP_VERSION: str = "1.0.0"
    DEBUG: bool = True
    
    # Server
    HOST: str = "0.0.0.0"
    PORT: int = 8000
    
    # Database (PostgreSQL)
    DATABASE_URL: str = "postgresql+asyncpg://menuflow:menuflow123@localhost:5432/menuflow"
    DATABASE_POOL_SIZE: int = 20
    DATABASE_MAX_OVERFLOW: int = 10
    DATABASE_POOL_TIMEOUT: int = 30
    DATABASE_POOL_RECYCLE: int = 3600
    DATABASE_ECHO: bool = False
    
    # Redis
    REDIS_URL: str = "redis://localhost:6379"
    REDIS_PASSWORD: str = ""
    REDIS_DB: int = 0
    REDIS_MAX_CONNECTIONS: int = 50
    
    # Kafka
    KAFKA_ENABLED: bool = False
    KAFKA_BOOTSTRAP_SERVERS: str = "localhost:9092"
    KAFKA_GROUP_ID: str = "menuflow-ai"
    KAFKA_AUTO_OFFSET_RESET: str = "earliest"
    KAFKA_ENABLE_AUTO_COMMIT: bool = False
    KAFKA_AUTO_COMMIT_INTERVAL_MS: int = 5000
    
    # Kafka Topics
    KAFKA_TOPIC_ORDERS: str = "menuflow-orders"
    KAFKA_TOPIC_CHATBOT: str = "menuflow-chatbot"
    KAFKA_TOPIC_NOTIFICATIONS: str = "menuflow-notifications"
    KAFKA_TOPIC_ANALYTICS: str = "menuflow-analytics"
    
    # Backend API
    BACKEND_API_URL: str = "http://localhost:8080/api/v1"
    BACKEND_API_TIMEOUT: int = 30
    
    # CORS
    CORS_ORIGINS: List[AnyHttpUrl] = [
        "http://localhost",
        "http://localhost:3000",
        "http://localhost:8080",
        "http://menuflow.local",
    ]
    
    # Trusted Hosts
    TRUSTED_HOSTS: List[str] = ["localhost", "127.0.0.1", "menuflow.local"]
    
    # Authentication
    SECRET_KEY: str = "your-secret-key-here-change-in-production"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60
    REFRESH_TOKEN_EXPIRE_DAYS: int = 7
    
    # Security
    ALLOWED_HOSTS: List[str] = ["localhost", "127.0.0.1"]
    
    # AI Models
    OPENAI_API_KEY: str = ""
    OPENAI_MODEL: str = "gpt-4o-mini"
    OPENAI_TIMEOUT: int = 60
    
    # File Storage
    UPLOAD_DIR: str = "./static/uploads"
    MAX_UPLOAD_SIZE_MB: int = 10
    
    # Logging
    LOG_LEVEL: str = "INFO"
    LOG_FORMAT: str = "json"
    
    # Rate Limiting
    RATE_LIMIT_REQUESTS: int = 100
    RATE_LIMIT_PERIOD: int = 60  # seconds
    
    # WhatsApp
    WHATSAPP_BUSINESS_ACCOUNT_ID: str = ""
    WHATSAPP_PHONE_NUMBER_ID: str = ""
    WHATSAPP_ACCESS_TOKEN: str = ""
    WHATSAPP_WEBHOOK_VERIFY_TOKEN: str = ""
    
    @classmethod
    def get_env_file(cls) -> str:
        """Get the environment file path"""
        return ".env"


# Create settings instance
@lru_cache()
def get_settings():
    """Get settings instance (cached)"""
    return Settings()


# Settings instance
settings = get_settings()
