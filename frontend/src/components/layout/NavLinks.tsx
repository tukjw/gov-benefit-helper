import { Link, useLocation } from "react-router";

// 조건 비교·관심 혜택 메뉴는 #74에서 붙인다.
const NAV_ITEMS = [{ href: "/benefits", label: "전체 혜택" }];

type Props = {
  className?: string;
  linkClassName?: string;
  onNavigate?: () => void;
};

export function NavLinks({ className, linkClassName, onNavigate }: Props) {
  const { pathname } = useLocation();
  return (
    <ul className={className}>
      {NAV_ITEMS.map((item) => {
        const current = pathname === item.href || pathname.startsWith(`${item.href}/`);
        return (
          <li key={item.href}>
            <Link
              to={item.href}
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
