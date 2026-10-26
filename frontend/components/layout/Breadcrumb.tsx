import Link from "next/link";
import styles from "./Breadcrumb.module.css";

export type BreadcrumbItem = { label: string; href?: string };

// 마지막 항목이 현재 화면이다. 홈은 항상 맨 앞에 붙는다.
export function Breadcrumb({ items }: { items: BreadcrumbItem[] }) {
  const all: BreadcrumbItem[] = [{ label: "홈", href: "/" }, ...items];
  return (
    <nav aria-label="현재 위치" className={styles.breadcrumb}>
      <ol>
        {all.map((item, index) => {
          const last = index === all.length - 1;
          return (
            <li key={`${index}-${item.label}`}>
              {last || !item.href ? (
                <span aria-current={last ? "page" : undefined}>{item.label}</span>
              ) : (
                <Link href={item.href}>{item.label}</Link>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
