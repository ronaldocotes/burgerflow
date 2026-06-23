package com.burgerflow.repository.tenant

import com.burgerflow.model.Ingredient
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface IngredientRepository : JpaRepository<Ingredient, UUID> {

    /**
     * Pessimistic lock for stock decrement during order creation: two concurrent
     * orders consuming the same ingredient must serialize, otherwise both could
     * read the same stock and oversell (lost update). conhecimento Seç.3.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Ingredient i WHERE i.id IN :ids")
    fun findAllByIdsForUpdate(@Param("ids") ids: Collection<UUID>): List<Ingredient>

    fun existsByName(name: String): Boolean
}
