import type { RecordData } from "../../domain/types";
export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  text: string;
  links?: { label: string; url: string }[];
  proposal?: RecordData;
  resolution?: "created" | "cancelled";
  createdUrl?: string;
}
