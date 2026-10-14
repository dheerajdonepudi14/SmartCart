package com.smartcart.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    @Override
    @EntityGraph(attributePaths = "category")
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Optional<Product> findWithCategoryById(Long id);

    /** Row locks prevent two concurrent checkouts from overselling the same stock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> lockAllByIdIn(@Param("ids") Collection<Long> ids);

    @Query("select p.name from Product p where p.active = true and lower(p.name) like lower(concat('%', :q, '%')) escape '!' order by p.ratingCount desc")
    List<String> suggestNames(@Param("q") String q, Pageable pageable);

    /** "Customers who bought this also bought": product ids co-occurring in orders with the given statuses. */
    @Query("""
            select oi2.product.id from OrderItem oi2
            where oi2.order.id in (
                select oi.order.id from OrderItem oi
                where oi.product.id = :productId and oi.order.status in :statuses)
              and oi2.product.id <> :productId
            group by oi2.product.id
            order by count(oi2.id) desc
            """)
    List<Long> findAlsoBoughtIds(@Param("productId") Long productId,
                                 @Param("statuses") Collection<com.smartcart.order.OrderStatus> statuses,
                                 Pageable pageable);

    @EntityGraph(attributePaths = "category")
    @Query("select p from Product p where p.active = true and p.category.id = :categoryId and p.id <> :excludeId order by p.ratingAvg desc, p.ratingCount desc")
    List<Product> findSimilar(@Param("categoryId") Long categoryId, @Param("excludeId") Long excludeId, Pageable pageable);

    @EntityGraph(attributePaths = "category")
    @Query("select p from Product p where p.active = true order by p.ratingAvg desc, p.ratingCount desc")
    List<Product> findTopRated(Pageable pageable);
}
