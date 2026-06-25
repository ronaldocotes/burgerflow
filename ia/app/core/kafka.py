"""
Kafka configuration and connection management
"""

import asyncio
import logging
from contextlib import asynccontextmanager
from typing import AsyncGenerator, Optional

from confluent_kafka import Consumer, Producer, KafkaException
from confluent_kafka.admin import AdminClient, NewTopic

from app.core.config import settings

logger = logging.getLogger(__name__)


# Kafka configuration
kafka_config = {
    "bootstrap.servers": settings.KAFKA_BOOTSTRAP_SERVERS,
    "group.id": settings.KAFKA_GROUP_ID,
    "auto.offset.reset": settings.KAFKA_AUTO_OFFSET_RESET,
    "enable.auto.commit": settings.KAFKA_ENABLE_AUTO_COMMIT,
    "auto.commit.interval.ms": settings.KAFKA_AUTO_COMMIT_INTERVAL_MS,
}

# Global Kafka instances
_kafka_producer: Optional[Producer] = None
_kafka_consumer: Optional[Consumer] = None
_kafka_admin: Optional[AdminClient] = None


def get_kafka_producer() -> Producer:
    """Get Kafka producer instance"""
    global _kafka_producer
    if _kafka_producer is None:
        _kafka_producer = Producer(kafka_config)
    return _kafka_producer


def get_kafka_consumer() -> Consumer:
    """Get Kafka consumer instance"""
    global _kafka_consumer
    if _kafka_consumer is None:
        _kafka_consumer = Consumer(kafka_config)
    return _kafka_consumer


def get_kafka_admin() -> AdminClient:
    """Get Kafka admin client instance"""
    global _kafka_admin
    if _kafka_admin is None:
        _kafka_admin = AdminClient({"bootstrap.servers": settings.KAFKA_BOOTSTRAP_SERVERS})
    return _kafka_admin


async def init_kafka():
    """Initialize Kafka connection and create topics if needed"""
    if not settings.KAFKA_ENABLED:
        logger.info("Kafka disabled; skipping broker initialization")
        return

    logger.info("Initializing Kafka connection...")
    
    try:
        # Test producer connection
        producer = get_kafka_producer()
        
        # Create topics if they don't exist
        await create_kafka_topics()
        
        logger.info("Kafka connection established successfully")
    except Exception as e:
        logger.error(f"Failed to initialize Kafka: {e}", exc_info=True)
        raise


async def close_kafka():
    """Close Kafka connection"""
    if not settings.KAFKA_ENABLED:
        return

    logger.info("Closing Kafka connection...")
    
    global _kafka_producer, _kafka_consumer, _kafka_admin
    
    try:
        if _kafka_producer is not None:
            _kafka_producer.flush()
            _kafka_producer = None
        
        if _kafka_consumer is not None:
            _kafka_consumer.close()
            _kafka_consumer = None
        
        if _kafka_admin is not None:
            _kafka_admin = None
        
        logger.info("Kafka connection closed successfully")
    except Exception as e:
        logger.error(f"Failed to close Kafka: {e}", exc_info=True)
        raise


async def create_kafka_topics():
    """Create required Kafka topics if they don't exist"""
    topics = [
        settings.KAFKA_TOPIC_ORDERS,
        settings.KAFKA_TOPIC_CHATBOT,
        settings.KAFKA_TOPIC_NOTIFICATIONS,
        settings.KAFKA_TOPIC_ANALYTICS,
    ]
    
    admin = get_kafka_admin()
    
    for topic in topics:
        try:
            # Check if topic exists
            topic_metadata = admin.list_topics(timeout=5)
            if topic not in topic_metadata.topics:
                # Create topic
                new_topic = NewTopic(
                    topic,
                    num_partitions=3,
                    replication_factor=1,
                )
                fs = admin.create_topics([new_topic])
                
                # Wait for topic creation to complete
                for topic, f in fs.items():
                    if f.result() is not None:
                        logger.info(f"Created Kafka topic: {topic}")
        except Exception as e:
            logger.warning(f"Failed to create topic {topic}: {e}")


async def produce_message(topic: str, message: dict) -> bool:
    """Produce a message to Kafka"""
    if not settings.KAFKA_ENABLED:
        logger.debug("Kafka disabled; skipping message for topic %s: %s", topic, message)
        return False

    producer = get_kafka_producer()
    
    try:
        # Serialize message to JSON
        import json
        message_json = json.dumps(message).encode("utf-8")
        
        # Produce message
        producer.produce(topic, value=message_json)
        producer.flush()
        
        logger.debug(f"Produced message to topic {topic}: {message}")
        return True
    except Exception as e:
        logger.error(f"Failed to produce message to topic {topic}: {e}", exc_info=True)
        return False


@asynccontextmanager
async def consume_messages(topic: str) -> AsyncGenerator[Consumer, None]:
    """Context manager for consuming Kafka messages"""
    consumer = Consumer({
        **kafka_config,
        "group.id": f"{settings.KAFKA_GROUP_ID}-{topic}",
    })
    
    try:
        consumer.subscribe([topic])
        yield consumer
    except Exception as e:
        logger.error(f"Failed to consume messages from topic {topic}: {e}", exc_info=True)
        raise
    finally:
        consumer.close()
