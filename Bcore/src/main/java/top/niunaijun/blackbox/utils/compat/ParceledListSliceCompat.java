package top.niunaijun.blackbox.utils.compat;

import java.lang.reflect.Method;
import java.util.List;

import black.android.content.pm.BRParceledListSlice;

public class ParceledListSliceCompat {

	public static boolean isReturnParceledListSlice(Method method) {
		return method != null && method.getReturnType() == BRParceledListSlice.getRealClass();
	}

	public static boolean isParceledListSlice(Object obj) {
		return obj != null && obj.getClass() == BRParceledListSlice.getRealClass();
	}

	// Builds the list type the hooked method is declared to return. Android 17
	// (API 37) made IPackageManager.getInstalledPackages return PackageInfoList,
	// a ParceledListSlice subclass; handing back a plain slice fails with
	// "Couldn't convert result of type ParceledListSlice to PackageInfoList".
	public static Object createFor(Method method, List<?> list) {
		Class<?> type = method.getReturnType();
		if (type != BRParceledListSlice.getRealClass() && BRParceledListSlice.getRealClass() != null
				&& BRParceledListSlice.getRealClass().isAssignableFrom(type)) {
			try {
				return type.getConstructor(List.class).newInstance(list);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException("Cannot build " + type.getName(), e);
			}
		}
		return create(list);
	}

	public static Object create(List<?> list) {
		Object slice = BRParceledListSlice.get()._new(list);
		if (slice != null) {
			return slice;
		} else {
			slice = BRParceledListSlice.get()._new();
		}
		for (Object item : list) {
			BRParceledListSlice.get(slice).append(item);
		}
		BRParceledListSlice.get(slice).setLastSlice(true);
		return slice;
	}
}
