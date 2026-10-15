import React from "react";

type BadgeVariant =
  | "success"
  | "warning"
  | "danger"
  | "info"
  | "neutral";

interface BadgeProps {
  status: string;
  variant?: BadgeVariant;
  className?: string;
}

export const Badge: React.FC<BadgeProps> = ({ status, variant, className = "" }) => {
  let resolvedVariant: BadgeVariant = variant ?? "neutral";

  if (!variant) {
    const s = status.toUpperCase();
    if (["CONFIRMED", "FULFILLED", "SUCCEEDED", "RESOLVED"].includes(s)) {
      resolvedVariant = "success";
    } else if (["PENDING_PAYMENT", "PENDING", "RETRYING", "REPLAYED"].includes(s)) {
      resolvedVariant = "info";
    } else if (["ACTION_REQUIRED", "TIMED_OUT", "OPEN"].includes(s)) {
      resolvedVariant = "warning";
    } else if (["CANCELLED", "FAILED", "DEAD_LETTERED", "DISMISSED"].includes(s)) {
      resolvedVariant = "danger";
    }
  }

  return (
    <span className={`status-badge badge-${resolvedVariant} ${className}`}>
      <span className="badge-dot" />
      {status.replace(/_/g, " ")}
    </span>
  );
};
