import { Loader2 } from "lucide-react";

type LoadingSpinnerProps = {
  size?: "sm" | "md" | "lg";
};

const sizes = {
  sm: "h-4 w-4",
  md: "h-6 w-6",
  lg: "h-8 w-8",
};

export default function LoadingSpinner({ size = "md" }: LoadingSpinnerProps) {
  return (
    <Loader2
      aria-label="Carregando"
      className={`${sizes[size]} animate-spin text-emerald-600`}
      role="status"
    />
  );
}
