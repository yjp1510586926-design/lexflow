import React from 'react';

interface SpinnerProps {
  size?: 'sm' | 'md' | 'lg';
  text?: string;
}

export const Spinner: React.FC<SpinnerProps> = ({ size = 'md', text }) => (
  <div className="spinner-container">
    <div className={`spinner spinner-${size}`} role="status">
      <span className="sr-only">加载中...</span>
    </div>
    {text && <p className="spinner-text">{text}</p>}
  </div>
);

export const Skeleton: React.FC<{
  height?: number | string;
  width?: number | string;
  className?: string;
}> = ({ height = 20, width = '100%', className = '' }) => (
  <div className={`skeleton ${className}`} style={{ height, width }} />
);
