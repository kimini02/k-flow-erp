import { Link, useNavigate, useParams } from "react-router-dom";
import { Alert, Button, Loader } from "@mantine/core";
import { modules, resourceByKey } from "../app/catalog";
import { useRecord } from "../api/hooks";
import { DocumentForm } from "../components/DocumentForm";

// Full-page entry for documents with line grids; saving returns to the list with the
// saved document open, so the list context is kept.
export function DocumentEditorPage() {
  const { module: moduleKey, screen, docId } = useParams();
  const navigate = useNavigate();
  const mod = modules.find((m) => m.key === moduleKey);
  const item = mod?.items.find((i) => i.key === screen);
  const resource = resourceByKey(item?.resource ?? "");
  const record = useRecord(resource?.key ?? "", docId ?? null);
  if (!mod || !item || !resource)
    return (
      <div className="empty-state">
        <h1>페이지를 찾을 수 없습니다</h1>
        <Link to="/">대시보드로 이동</Link>
      </div>
    );
  const listUrl = `/${mod.key}/${item.key}`;
  if (docId && record.isLoading) return <Loader />;
  if (docId && (record.error || !record.data))
    return (
      <Alert color="red" title="문서를 불러오지 못했습니다">
        {record.error?.message}
        <Button
          variant="light"
          color="red"
          ml="md"
          onClick={() => record.refetch()}
        >
          다시 시도
        </Button>
      </Alert>
    );
  return (
    <DocumentForm
      key={docId ?? "new"}
      variant="page"
      resource={resource}
      record={docId ? record.data : undefined}
      backLabel={`${item.label} 목록`}
      onClose={() =>
        navigate(
          docId ? `${listUrl}?doc=${encodeURIComponent(docId)}` : listUrl,
        )
      }
      onSaved={(saved) =>
        navigate(`${listUrl}?doc=${encodeURIComponent(saved.id)}`)
      }
    />
  );
}
