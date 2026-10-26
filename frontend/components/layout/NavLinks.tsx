import Link from "next/link";

// 조건 비교·관심 혜택 메뉴는 #74에서 붙인다.
const NAV_ITEMS = [{ href: "/benefits", label: "전체 혜택" }];

type Props = {
  className?: string;
  linkClassName?: string;
  // 알 수 없으면(서버 프리렌더링 중) 현재 메뉴 표시 없이 그린다.
  pathname?: string;
  onNavigate?: () => void;
};

export function NavLinks({ className, linkClassName, pathname, onNavigate }: Props) {
  return (
    <ul className={className}>
      {NAV_ITEMS.map((item) => {
        const current = pathname === item.href || pathname?.startsWith(`${item.href}/`);
        return (
          <li key={item.href}>
            <Link
              href={item.href}
              className={linkClassName}
              aria-current={current ? "page" : undefined}
              onClick={onNavigate}
            >
              {item.label}
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
