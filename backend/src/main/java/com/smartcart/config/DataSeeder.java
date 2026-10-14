package com.smartcart.config;

import com.smartcart.catalog.Category;
import com.smartcart.catalog.CategoryRepository;
import com.smartcart.catalog.Product;
import com.smartcart.catalog.ProductRepository;
import com.smartcart.catalog.ProductService;
import com.smartcart.user.Role;
import com.smartcart.user.User;
import com.smartcart.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds an admin account and a demo catalogue on first start (idempotent). Product photos are
 * royalty-free Unsplash CDN images; the frontend falls back to a placeholder if one fails to load.
 */
@Component
public class DataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String IMG = "https://images.unsplash.com/photo-%s?auto=format&fit=crop&w=800&q=70";

    private final AppProperties props;
    private final UserRepository users;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final PasswordEncoder encoder;

    public DataSeeder(AppProperties props, UserRepository users, CategoryRepository categories,
                      ProductRepository products, PasswordEncoder encoder) {
        this.props = props;
        this.users = users;
        this.categories = categories;
        this.products = products;
        this.encoder = encoder;
    }

    private record Seed(String category, String name, String description, int price, Integer mrp, int stock,
                        String photo, Map<String, String> attrs) {}

    private static final List<Seed> SEEDS = List.of(
        new Seed("Electronics", "Wireless Over-Ear Headphones", "40-hour battery, active noise cancellation and plush memory-foam ear cushions for all-day listening.", 4999, 7999, 40, "1505740420928-5e560c06d30e", Map.of("brand", "Sonora", "color", "Black", "connectivity", "Bluetooth 5.3")),
        new Seed("Electronics", "Studio Monitor Headphones", "Flat, accurate sound signature with detachable cable, ideal for mixing and podcasting.", 6499, 8999, 25, "1583394838336-acd977736f90", Map.of("brand", "Sonora", "driver", "50 mm")),
        new Seed("Electronics", "Portable Bluetooth Speaker", "IPX7 waterproof speaker with 360° sound and 12-hour playtime.", 2799, 3999, 60, "1608043152269-423dbba4e7e1", Map.of("brand", "Boomly", "waterproof", "IPX7")),
        new Seed("Electronics", "Mirrorless Camera Kit", "24 MP sensor, 4K video and a 15-45 mm kit lens in a travel-friendly body.", 58999, 64999, 8, "1526170375885-4d8ecf77b99f", Map.of("brand", "Optix", "sensor", "APS-C 24 MP")),
        new Seed("Electronics", "Ultrabook 14\" Laptop", "Lightweight 1.2 kg laptop with 16 GB RAM, 512 GB SSD and an all-day battery.", 72990, 84990, 12, "1496181133206-80ce9b88a853", Map.of("ram", "16 GB", "storage", "512 GB SSD")),
        new Seed("Electronics", "Smartphone 128 GB", "6.5-inch AMOLED display, triple camera and 5000 mAh battery with fast charging.", 18999, 22999, 35, "1511707171634-5f897ff02aa9", Map.of("storage", "128 GB", "display", "6.5\" AMOLED")),

        new Seed("Fashion", "Classic Analog Wristwatch", "Stainless steel case, sapphire-coated glass and a genuine leather strap.", 3499, 5999, 30, "1523275335684-37898b6baf30", Map.of("brand", "Tempo", "strap", "Leather")),
        new Seed("Fashion", "Minimal Chronograph Watch", "Clean dial chronograph with 5 ATM water resistance.", 4299, 6499, 18, "1546868871-7041f2a55e12", Map.of("brand", "Tempo", "water resistance", "5 ATM")),
        new Seed("Fashion", "Polarised Aviator Sunglasses", "UV400 polarised lenses with a lightweight metal frame.", 1299, 2499, 70, "1572635196237-14b3f281503f", Map.of("lens", "Polarised UV400")),
        new Seed("Fashion", "Everyday Canvas Backpack", "Water-resistant 22 L backpack with a padded 15-inch laptop sleeve.", 1899, 2999, 55, "1553062407-98eeb64c6a62", Map.of("capacity", "22 L", "material", "Canvas")),

        new Seed("Footwear", "Red Performance Running Shoes", "Responsive foam midsole and breathable knit upper for daily runs.", 3999, 5499, 45, "1542291026-7eec264c27ff", Map.of("brand", "Stride", "type", "Running")),
        new Seed("Footwear", "Lifestyle Sneakers", "Cushioned everyday sneakers with a durable rubber outsole.", 2999, 4499, 50, "1491553895911-0055eca6402d", Map.of("brand", "Stride", "type", "Casual")),

        new Seed("Home & Kitchen", "Ceramic Pour-Over Coffee Set", "Hand-glazed dripper, carafe and two cups for a slow morning brew.", 1599, 2299, 40, "1495474472287-4d71bcdd2085", Map.of("material", "Ceramic")),
        new Seed("Home & Kitchen", "Insulated Steel Water Bottle", "Keeps drinks cold for 24 hours or hot for 12; 750 ml, leak-proof lid.", 799, 1199, 120, "1602143407151-7111542de6e8", Map.of("capacity", "750 ml")),
        new Seed("Home & Kitchen", "Espresso Coffee Cup Pair", "Double-walled glass cups that keep espresso hot and hands cool.", 649, 999, 80, "1509042239860-f550ce710b93", Map.of("material", "Borosilicate glass")),

        new Seed("Beauty", "Hydrating Skincare Set", "Cleanser, serum and moisturiser with hyaluronic acid for all skin types.", 1499, 2199, 65, "1556228578-8c89e6adf883", Map.of("skin type", "All")),
        new Seed("Beauty", "Eau de Parfum 100 ml", "Warm woody fragrance with notes of sandalwood, amber and vanilla.", 2499, 3499, 28, "1585386959984-a4155224a1ad", Map.of("volume", "100 ml")),

        new Seed("Books", "The Art of Clean Code (Paperback)", "A practical guide to writing readable, maintainable software.", 499, 699, 100, "1544947950-fa07a98d237f", Map.of("format", "Paperback", "pages", "320")),
        new Seed("Books", "Stories for Quiet Evenings", "An anthology of short fiction from contemporary Indian writers.", 399, 599, 90, "1512820790803-83ca734da794", Map.of("format", "Paperback", "language", "English"))
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!props.seed().enabled()) {
            return;
        }
        seedAdmin();
        if (products.count() > 0) {
            return;
        }
        Map<String, Category> byName = new HashMap<>();
        for (Seed s : SEEDS) {
            Category c = byName.computeIfAbsent(s.category(),
                    n -> categories.save(new Category(n, ProductService.slugify(n))));
            Product p = new Product();
            p.setName(s.name());
            p.setDescription(s.description());
            p.setPrice(BigDecimal.valueOf(s.price()).setScale(2));
            p.setMrp(s.mrp() == null ? null : BigDecimal.valueOf(s.mrp()).setScale(2));
            p.setStock(s.stock());
            p.setCategory(c);
            p.setImageUrl(String.format(IMG, s.photo()));
            p.setImages(List.of(String.format(IMG, s.photo())));
            p.setAttributes(s.attrs());
            products.save(p);
        }
        log.info("Seeded {} categories and {} products", byName.size(), SEEDS.size());
    }

    private void seedAdmin() {
        String email = props.seed().adminEmail();
        if (email == null || email.isBlank() || users.existsByEmailIgnoreCase(email)) {
            return;
        }
        users.save(new User("Store Admin", email.toLowerCase(), encoder.encode(props.seed().adminPassword()), Role.ROLE_ADMIN));
        log.info("Seeded admin account {}", email);
    }
}
