import { ExternalLink as ExternalLinkIcon } from "lucide-react";
import type { ReactNode } from "react";

export function ExternalLink({
  href,
  className = "text-link",
  children,
}: {
  href: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <a href={href} className={className} target="_blank" rel="noopener noreferrer">
      {children}
      <ExternalLinkIcon size={16} aria-hidden="true" style={{ marginLeft: "0.25rem", verticalAlign: "-0.125em" }} />
      <span className="visually-hidden">(새 창)</span>
    </a>
  );
}
