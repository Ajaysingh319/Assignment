import React from "react";

interface ErrorBannerProps {
  message: string;
  statusCode?: number;
  onRetry?: () => void;
  className?: string;
}

export const ErrorBanner: React.FC<ErrorBannerProps> = ({
  message,
  statusCode,
  onRetry,
  className = "",
}) => {
  return (
    <div className={`error-banner ${className}`} role="alert">
      <div className="error-banner-content">
        <svg
          className="error-icon"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
          width="20"
          height="20"
          aria-hidden="true"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
          />
        </svg>
        <div className="error-text">
          <p className="error-title">
            {statusCode ? `Error (${statusCode})` : "Operation Failed"}
          </p>
          <p className="error-message">{message}</p>
        </div>
      </div>
      {onRetry && (
        <button type="button" className="retry-btn" onClick={onRetry}>
          Try Again
        </button>
      )}
    </div>
  );
};
