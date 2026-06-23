"""
Demand Forecasting API Endpoints
"""

import logging
from datetime import datetime, timedelta
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, status, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.core.db import get_db
from app.core.kafka import produce_message
from app.core.config import settings
from sqlalchemy.ext.asyncio import AsyncSession

logger = logging.getLogger(__name__)

# Create router
router = APIRouter(prefix="/forecast", tags=["Demand Forecasting"])


# Pydantic models
class ForecastRequest(BaseModel):
    """Request model for demand forecasting"""
    
    tenant_id: str = Field(..., description="Tenant ID")
    product_ids: List[str] = Field(default_factory=list, description="List of product IDs to forecast")
    start_date: datetime = Field(..., description="Start date for forecasting")
    end_date: datetime = Field(..., description="End date for forecasting")
    forecast_horizon_days: int = Field(default=7, ge=1, le=30, description="Number of days to forecast")
    use_historical_data: bool = Field(default=True, description="Use historical data for forecasting")
    consider_weather: bool = Field(default=False, description="Consider weather data in forecasting")
    consider_events: bool = Field(default=False, description="Consider local events in forecasting")


class ForecastResponse(BaseModel):
    """Response model for demand forecasting"""
    
    tenant_id: str
    product_id: str
    product_name: str
    forecast_date: datetime
    predicted_quantity: int
    confidence_interval: tuple
    historical_average: Optional[float] = None
    trend: Optional[str] = None
    seasonality_factor: Optional[float] = None


class DemandForecast(BaseModel):
    """Complete demand forecast model"""
    
    tenant_id: str
    forecast_date: datetime
    total_predicted_demand: int
    product_forecasts: List[ForecastResponse]
    confidence_score: float
    historical_comparison: dict
    recommendations: List[str]


class BatchForecastRequest(BaseModel):
    """Request model for batch forecasting"""
    
    tenant_id: str
    forecast_horizon_days: int = Field(default=7, ge=1, le=30)
    consider_all_products: bool = Field(default=True)


class BatchForecastResponse(BaseModel):
    """Response model for batch forecasting"""
    
    tenant_id: str
    forecast_horizon_days: int
    total_forecasts: int
    forecasts: List[DemandForecast]
    generated_at: datetime


@router.post(
    "/",
    summary="Generate demand forecast",
    response_model=DemandForecast,
    status_code=status.HTTP_200_OK,
)
async def generate_forecast(
    request: ForecastRequest,
    db: AsyncSession = Depends(get_db)
):
    """
    Generate demand forecast for specific products
    
    This endpoint uses historical order data and machine learning models
    to predict future demand for products.
    """
    try:
        logger.info(f"Generating forecast for tenant {request.tenant_id}")
        
        # Validate dates
        if request.start_date >= request.end_date:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="start_date must be before end_date"
            )
        
        # In a real implementation, this would:
        # 1. Fetch historical order data from the database
        # 2. Apply ML models (ARIMA, Prophet, or custom models)
        # 3. Generate predictions
        # 4. Return formatted results
        
        # For demo purposes, return mock data
        product_forecasts = []
        
        for product_id in request.product_ids or ["default_product"]:
            for day in range(request.forecast_horizon_days):
                forecast_date = request.start_date + timedelta(days=day)
                
                # Mock data - in production, use actual ML models
                predicted_quantity = hash((product_id, forecast_date.isoformat())) % 100
                confidence_interval = (predicted_quantity - 10, predicted_quantity + 10)
                
                product_forecasts.append(ForecastResponse(
                    tenant_id=request.tenant_id,
                    product_id=product_id,
                    product_name=f"Product {product_id[:8]}",
                    forecast_date=forecast_date,
                    predicted_quantity=predicted_quantity,
                    confidence_interval=confidence_interval,
                    historical_average=float(predicted_quantity + 5),
                    trend="increasing" if predicted_quantity > 50 else "decreasing",
                    seasonality_factor=1.0 + (predicted_quantity % 10) / 100
                ))
        
        # Calculate totals
        total_predicted = sum(f.predicted_quantity for f in product_forecasts)
        
        # Create response
        response = DemandForecast(
            tenant_id=request.tenant_id,
            forecast_date=datetime.utcnow(),
            total_predicted_demand=total_predicted,
            product_forecasts=product_forecasts,
            confidence_score=0.95,
            historical_comparison={
                "average_demand": float(total_predicted / len(product_forecasts)),
                "max_demand": float(max(f.predicted_quantity for f in product_forecasts)),
                "min_demand": float(min(f.predicted_quantity for f in product_forecasts)),
            },
            recommendations=[
                f"Expect high demand for products on {(request.start_date + timedelta(days=3)).strftime('%Y-%m-%d')}",
                "Consider ordering extra ingredients for weekend",
                "Monitor inventory levels closely"
            ]
        )
        
        # Publish forecast event to Kafka
        await produce_message(
            settings.KAFKA_TOPIC_ANALYTICS,
            {
                "type": "forecast_generated",
                "tenant_id": request.tenant_id,
                "timestamp": datetime.utcnow().isoformat(),
                "forecast_horizon_days": request.forecast_horizon_days,
                "total_predicted_demand": total_predicted,
            }
        )
        
        return response
        
    except Exception as e:
        logger.error(f"Failed to generate forecast: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to generate forecast: {str(e)}"
        )


@router.post(
    "/batch",
    summary="Generate batch forecast for all products",
    response_model=BatchForecastResponse,
    status_code=status.HTTP_200_OK,
)
async def generate_batch_forecast(
    request: BatchForecastRequest,
    db: AsyncSession = Depends(get_db)
):
    """
    Generate demand forecast for all products in a tenant
    """
    try:
        logger.info(f"Generating batch forecast for tenant {request.tenant_id}")
        
        # In a real implementation, fetch all products and generate forecasts
        # For demo, return mock data
        
        forecasts = []
        product_ids = ["prod_001", "prod_002", "prod_003", "prod_004", "prod_005"]
        
        for product_id in product_ids:
            product_forecasts = []
            
            for day in range(request.forecast_horizon_days):
                forecast_date = datetime.utcnow() + timedelta(days=day)
                predicted_quantity = hash((product_id, forecast_date.isoformat())) % 50
                
                product_forecasts.append(ForecastResponse(
                    tenant_id=request.tenant_id,
                    product_id=product_id,
                    product_name=f"Product {product_id}",
                    forecast_date=forecast_date,
                    predicted_quantity=predicted_quantity,
                    confidence_interval=(predicted_quantity - 5, predicted_quantity + 5),
                    historical_average=float(predicted_quantity + 2),
                    trend="stable",
                    seasonality_factor=1.0
                ))
            
            total_predicted = sum(f.predicted_quantity for f in product_forecasts)
            
            forecasts.append(DemandForecast(
                tenant_id=request.tenant_id,
                forecast_date=datetime.utcnow(),
                total_predicted_demand=total_predicted,
                product_forecasts=product_forecasts,
                confidence_score=0.92,
                historical_comparison={
                    "average": float(total_predicted / len(product_forecasts)),
                    "max": float(max(f.predicted_quantity for f in product_forecasts)),
                    "min": float(min(f.predicted_quantity for f in product_forecasts)),
                },
                recommendations=[
                    f"Stock up on {product_id} for high demand period",
                    "Monitor sales trends"
                ]
            ))
        
        return BatchForecastResponse(
            tenant_id=request.tenant_id,
            forecast_horizon_days=request.forecast_horizon_days,
            total_forecasts=len(forecasts),
            forecasts=forecasts,
            generated_at=datetime.utcnow()
        )
        
    except Exception as e:
        logger.error(f"Failed to generate batch forecast: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to generate batch forecast: {str(e)}"
        )


@router.get(
    "/history",
    summary="Get historical demand data",
    status_code=status.HTTP_200_OK,
)
async def get_historical_demand(
    tenant_id: str = Query(..., description="Tenant ID"),
    product_id: Optional[str] = Query(None, description="Product ID (optional)"),
    start_date: Optional[datetime] = Query(None, description="Start date"),
    end_date: Optional[datetime] = Query(None, description="End date"),
    db: AsyncSession = Depends(get_db)
):
    """
    Get historical demand data for analysis
    """
    try:
        logger.info(f"Fetching historical demand for tenant {tenant_id}")
        
        # In a real implementation, fetch from database
        # For demo, return mock data
        
        historical_data = []
        
        for day in range(30):  # Last 30 days
            date = datetime.utcnow() - timedelta(days=day)
            
            if product_id:
                # Single product
                quantity = hash((product_id, date.isoformat())) % 50
                historical_data.append({
                    "date": date.isoformat(),
                    "product_id": product_id,
                    "quantity": quantity,
                    "revenue": quantity * 25.50,
                })
            else:
                # All products
                for pid in ["prod_001", "prod_002", "prod_003"]:
                    quantity = hash((pid, date.isoformat())) % 30
                    historical_data.append({
                        "date": date.isoformat(),
                        "product_id": pid,
                        "quantity": quantity,
                        "revenue": quantity * 25.50,
                    })
        
        return JSONResponse(
            content={
                "tenant_id": tenant_id,
                "product_id": product_id,
                "start_date": start_date.isoformat() if start_date else None,
                "end_date": end_date.isoformat() if end_date else None,
                "data": historical_data,
                "total_records": len(historical_data),
            }
        )
        
    except Exception as e:
        logger.error(f"Failed to fetch historical demand: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to fetch historical demand: {str(e)}"
        )


@router.get(
    "/trends",
    summary="Get demand trends",
    status_code=status.HTTP_200_OK,
)
async def get_demand_trends(
    tenant_id: str = Query(..., description="Tenant ID"),
    timeframe: str = Query(default="weekly", description="Timeframe (daily, weekly, monthly)"),
    db: AsyncSession = Depends(get_db)
):
    """
    Get demand trends over time
    """
    try:
        logger.info(f"Fetching demand trends for tenant {tenant_id}")
        
        # Mock trend data
        trends = {
            "weekly": [
                {"period": "Week 1", "demand": 150, "growth": 10.5},
                {"period": "Week 2", "demand": 175, "growth": 16.7},
                {"period": "Week 3", "demand": 200, "growth": 14.3},
                {"period": "Week 4", "demand": 180, "growth": -10.0},
            ],
            "monthly": [
                {"period": "January", "demand": 600, "growth": 5.2},
                {"period": "February", "demand": 750, "growth": 25.0},
                {"period": "March", "demand": 850, "growth": 13.3},
                {"period": "April", "demand": 900, "growth": 5.9},
            ],
            "daily": [
                {"period": "Monday", "demand": 50, "growth": 15.0},
                {"period": "Tuesday", "demand": 60, "growth": 20.0},
                {"period": "Wednesday", "demand": 70, "growth": 16.7},
                {"period": "Thursday", "demand": 80, "growth": 14.3},
                {"period": "Friday", "demand": 120, "growth": 50.0},
                {"period": "Saturday", "demand": 150, "growth": 25.0},
                {"period": "Sunday", "demand": 90, "growth": -40.0},
            ],
        }
        
        return JSONResponse(
            content={
                "tenant_id": tenant_id,
                "timeframe": timeframe,
                "trends": trends.get(timeframe, trends["weekly"]),
                "summary": {
                    "average_growth": 12.5,
                    "highest_demand_day": "Saturday",
                    "lowest_demand_day": "Monday",
                },
            }
        )
        
    except Exception as e:
        logger.error(f"Failed to fetch demand trends: {e}", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to fetch demand trends: {str(e)}"
        )
