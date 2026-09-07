package com.integration.stay.storage.catalog;

/**
 * 적용 도중 우리 불변식이 깨졌다.
 *
 * <p>여기서 잡지 않으면 뒤이은 FK 위반이나 NPE 로 원인이 흐려진다.
 * 트랜잭션 안에서 던지므로 전체가 롤백된다.
 */
public class CatalogApplyInvariantViolation extends RuntimeException {

    public CatalogApplyInvariantViolation(String detail) {
        super("catalog apply invariant violated: " + detail);
    }
}
