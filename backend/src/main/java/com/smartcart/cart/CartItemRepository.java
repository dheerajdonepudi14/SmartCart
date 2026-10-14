package com.smartcart.cart;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    @EntityGraph(attributePaths = "product")
    List<CartItem> findByUserIdOrderByIdAsc(Long userId);

    /** Product ids and quantities only, so products can be row-locked before being loaded. */
    @Query("select c.product.id as productId, c.quantity as quantity from CartItem c where c.user.id = :userId")
    List<CartQuantity> findQuantities(@Param("userId") Long userId);

    interface CartQuantity {
        Long getProductId();
        Integer getQuantity();
    }

    Optional<CartItem> findByUserIdAndProductId(Long userId, Long productId);

    @Modifying
    @Query("delete from CartItem c where c.user.id = :userId and c.product.id in :productIds")
    int deleteByUserIdAndProductIdIn(@Param("userId") Long userId, @Param("productIds") Collection<Long> productIds);

    @Modifying
    @Query("delete from CartItem c where c.user.id = :userId")
    int deleteAllForUser(@Param("userId") Long userId);
}
