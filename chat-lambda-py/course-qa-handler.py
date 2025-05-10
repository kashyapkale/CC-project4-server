# file: course_qa_handler.py
# ------------------------------------------------------------
# Simplest Course‑QA Lambda (Python 3.10+)
# • Downloads a plain‑text syllabus from S3 once per warm container
# • Answers questions with a Bedrock LLM (Claude 3 Sonnet / Titan)
# ------------------------------------------------------------

import os, sys, json, boto3, logging

# optional: include libs in a python/ folder inside the ZIP
sys.path.append(os.path.join(os.path.dirname(__file__), "python"))

# ---------- logging ----------
logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

# ---------- AWS clients ----------
REGION  = os.getenv("AWS_REGION", "us-east-2")
s3      = boto3.client("s3", region_name=REGION)
bedrock = boto3.client("bedrock-runtime", region_name=REGION)

# ---------- Hot‑start cache ----------
_doc_text = None   # type: str | None

# ------------------------------------------------------------
def lambda_handler(event, context):
    """
    API Gateway proxy integration.
    Invoke: GET /qa?q=When%20is%20the%20midterm
    """
    try:
        question = event["queryStringParameters"]["q"]
    except Exception:
        return _resp(400, {"error": "Query parameter ?q=... is required"})

    _load_document_if_needed()     # download once per container
    answer = _chat(question, _doc_text)
    return _resp(200, {"answer": answer})

# ------------------------------------------------------------
#                        Helpers
# ------------------------------------------------------------
def _load_document_if_needed():
    global _doc_text
    if _doc_text is not None:
        return

    bucket  = os.environ["BUCKET_NAME"]
    doc_key = os.environ["DOC_KEY"]

    obj = s3.get_object(Bucket=bucket, Key=doc_key)
    _doc_text = obj["Body"].read().decode("utf-8")

    preview = _doc_text[:120].replace("\n", " ") + ("…" if len(_doc_text) > 120 else "")
    log.info("Loaded course document (%s chars). Preview: %s", len(_doc_text), preview)

def _chat(question: str, context_text: str) -> str:
    prompt = f"""You are a helpful course assistant.
Use ONLY the context below to answer the student's question.
If the answer is not in the context, reply "I don't know."

### Context
{context_text}

### Question
{question}

### Answer
"""
    body_json = json.dumps({
        "prompt": prompt,
        "max_tokens": 400,
        "temperature": 0.2
    })

    resp = bedrock.invoke_model(
        modelId     = os.environ["BEDROCK_LLM_ID"],
        contentType = "application/json",
        accept      = "application/json",
        body        = body_json.encode("utf-8")
    )
    return json.loads(resp["body"].read())["completion"]

def _resp(code: int, body_obj: dict):
    return {
        "statusCode": code,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(body_obj, ensure_ascii=False),
    }