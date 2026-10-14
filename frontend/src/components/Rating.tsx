export function Rating({ value, count }: { value: number; count?: number }) {
  const rounded = Math.round(Number(value) * 2) / 2;
  const label = `Rated ${Number(value).toFixed(1)} out of 5${count !== undefined ? `, ${count} reviews` : ""}`;
  return (
    <span className="rating" aria-label={label} title={label}>
      <span aria-hidden="true" className="rating__stars">
        {[1, 2, 3, 4, 5].map((i) => (
          <span key={i} className={i <= rounded ? "star star--on" : i - 0.5 === rounded ? "star star--half" : "star"}>
            ★
          </span>
        ))}
      </span>
      {count !== undefined && (
        <span aria-hidden="true" className="rating__count">
          ({count})
        </span>
      )}
    </span>
  );
}
