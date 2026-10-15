import React from "react";

interface SpinnerProps {
  size?: "sm" | "md" | "lg";
  label?: string;
}

export const Spinner: React.FC<SpinnerProps> = ({ size = "md", label }) => {
  return (
    <div className={`spinner-container spinner-${size}`} role="status">
      <div className="spinner-circle" />
      {label && <span className="spinner-label">{label}</span>}
      <span className="sr-only">Loading...</span>
    </div>
  );
};
