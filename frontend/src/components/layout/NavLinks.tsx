import { Link, useLocation } from "react-router";

// 프로필 입력 화면은 조건 비교의 한 단계라 조건 비교 메뉴를 현재 메뉴로 표시한다.
const NAV_ITEMS = [
  { href: "/match", label: "조건 비교", also: ["/profile"] },
  { href: "/benefits", label: "전체 혜택", also: [] },
  { href: "/bookmarks", label: "관심 혜택", also: [] },
];

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
        const current = [item.href, ...item.also].some((href) => pathname === href || pathname.startsWith(`${href}/`));
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
