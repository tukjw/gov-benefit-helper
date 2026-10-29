import { useToast } from "@/lib/toast";
import styles from "./Toast.module.css";

// 알림 영역은 늘 두고 내용만 바꾼다. 영역이 내용과 함께 생기면 화면 낭독기가 읽지 않을 수 있다.
export function ToastRegion() {
  const toast = useToast();
  return (
    <div role="status" className={styles.region}>
      {toast && (
        <p key={toast.id} className={styles.toast}>
          {toast.message}
        </p>
      )}
    </div>
  );
}
