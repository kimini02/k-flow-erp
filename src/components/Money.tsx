import { won } from "../domain/helpers";
export function Money({ value }: { value: number }) {
  return <span className="money">{won(value)}</span>;
}
