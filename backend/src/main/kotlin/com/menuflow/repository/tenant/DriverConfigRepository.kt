package com.menuflow.repository.tenant

import com.menuflow.model.DriverConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface DriverConfigRepository : JpaRepository<DriverConfig, UUID> {

    /** Config de remuneracao do entregador (no maximo uma, garantido por UNIQUE). */
    fun findByDriverId(driverId: UUID): DriverConfig?
}
